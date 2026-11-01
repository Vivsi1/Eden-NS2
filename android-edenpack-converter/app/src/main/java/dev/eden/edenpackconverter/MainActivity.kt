package dev.eden.edenpackconverter

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var source: Uri? = null
    private var sourceLabel = "No folder selected"
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseButton: Button
    private lateinit var convertButton: Button
    @Volatile private var cancelled = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source = uri
            sourceLabel = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            status.text = "Source: " + sourceLabel
            convertButton.isEnabled = true
        }
    }
    private val outputPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) startConversion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            gravity = Gravity.TOP
        }
        val title = TextView(this).apply { text = "EdenPack Converter"; textSize = 26f }
        val subtitle = TextView(this).apply {
            text = "Single-pass EdenPack v1 converter. Local Downloads folders use direct filesystem access when permitted."
            textSize = 15f
            setPadding(0, 16, 0, 24)
        }
        status = TextView(this).apply { text = sourceLabel; textSize = 14f; setPadding(0, 12, 0, 12) }
        chooseButton = Button(this).apply {
            text = "Choose game folder"
            setOnClickListener { folderPicker.launch(null) }
        }
        convertButton = Button(this).apply {
            text = "Convert to .edenpack"
            isEnabled = false
            setOnClickListener {
                val uri = source ?: return@setOnClickListener
                val local = resolveLocalTree(uri)
                if (local != null && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    status.text = "Allow all-files access for faster local Downloads conversion, then return and tap Convert again."
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + packageName)))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                    return@setOnClickListener
                }
                outputPicker.launch((sourceLabel.substringBeforeLast('.', sourceLabel) + ".edenpack").ifBlank { "Game.edenpack" })
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(chooseButton, match())
        root.addView(status)
        root.addView(convertButton, match())
        root.addView(progress, match())
        setContentView(root)
    }

    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun resolveLocalTree(uri: Uri): File? {
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val split = id.split(":", limit = 2)
            if (split.size != 2 || !split[0].equals("primary", ignoreCase = true)) return null
            val base = Environment.getExternalStorageDirectory()
            val target = File(base, split[1]).canonicalFile
            val canonicalBase = base.canonicalFile
            if (target.path != canonicalBase.path && !target.path.startsWith(canonicalBase.path + File.separator)) return null
            if (target.isDirectory) target else null
        } catch (_: Exception) { null }
    }

    private data class SourceNode(
        val name: String,
        val isDirectory: Boolean,
        val listChildren: () -> List<SourceNode>,
        val open: () -> InputStream
    )

    private fun fileNode(file: File): SourceNode = SourceNode(
        file.name, file.isDirectory,
        { (file.listFiles() ?: error("Cannot list " + file.path)).map { fileNode(it) } },
        { FileInputStream(file) }
    )

    private fun documentNode(doc: DocumentFile): SourceNode = SourceNode(
        doc.name ?: error("A source item has no filename."),
        doc.isDirectory,
        { doc.listFiles().map { documentNode(it) } },
        { contentResolver.openInputStream(doc.uri) ?: error("Cannot read " + doc.name) }
    )

    private fun startConversion(outputUri: Uri) {
        val sourceUri = source ?: return
        cancelled = false
        chooseButton.isEnabled = false
        convertButton.isEnabled = false
        status.text = "Starting single-pass conversion…"
        progress.progress = 0
        progress.isIndeterminate = true
        executor.execute {
            try {
                val localRoot = resolveLocalTree(sourceUri)
                val direct = localRoot != null && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
                val roots = if (direct) {
                    (localRoot!!.listFiles() ?: error("Cannot list " + localRoot.path)).map { fileNode(it) }
                } else {
                    val docRoot = DocumentFile.fromTreeUri(this, sourceUri) ?: error("Cannot open selected folder.")
                    docRoot.listFiles().map { documentNode(it) }
                }
                fun findTop(name: String) = roots.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val exefs = findTop("ExeFS") ?: error("Selected folder must directly contain ExeFS/.")
                val romfs = findTop("RomFS") ?: error("Selected folder must directly contain RomFS/.")
                require(exefs.isDirectory && romfs.isDirectory) { "ExeFS and RomFS must be directories." }

                val pfd = contentResolver.openFileDescriptor(outputUri, "rwt")
                    ?: error("Output provider did not provide a writable file.")
                pfd.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { fos ->
                        val channel = fos.channel
                        fun writeBytes(bytes: ByteArray, length: Int = bytes.size) {
                            val buffer = ByteBuffer.wrap(bytes, 0, length)
                            while (buffer.hasRemaining()) channel.write(buffer)
                        }
                        val zeros = ByteArray(64 * 1024)
                        fun writeZeros(count: Long) {
                            var remaining = count
                            while (remaining > 0) {
                                val n = minOf(remaining, zeros.size.toLong()).toInt()
                                writeBytes(zeros, n)
                                remaining -= n
                            }
                        }
                        fun align() {
                            val padding = ((channel.position() + 4095L) and -4096L) - channel.position()
                            if (padding > 0) writeZeros(padding)
                        }

                        writeZeros(32)
                        val index = JSONArray()
                        val buffer = ByteArray(1024 * 1024)
                        var bytesWritten = 0L
                        var entries = 0L
                        var files = 0L
                        val started = System.nanoTime()
                        fun update(path: String) {
                            val elapsed = (System.nanoTime() - started) / 1_000_000_000L
                            runOnUiThread {
                                progress.isIndeterminate = false
                                status.text = "Packing: " + path + "\n" + files + " files · " + entries +
                                    " entries\n" + formatBytes(bytesWritten) + " written · " + elapsed + "s"
                            }
                        }
                        fun addDirectory(path: String, node: SourceNode) {
                            if (cancelled) error("Conversion cancelled.")
                            index.put(JSONObject().put("path", path).put("type", "directory"))
                            entries++
                            update(path)
                            for (child in node.listChildren()) {
                                if (cancelled) error("Conversion cancelled.")
                                val name = child.name
                                require(name.isNotBlank() && name != "." && name != ".." &&
                                    '/' !in name && '\\' !in name && '\u0000' !in name) {
                                    "Unsupported filename: " + name
                                }
                                val childPath = path + "/" + name
                                if (child.isDirectory) {
                                    addDirectory(childPath, child)
                                } else {
                                    align()
                                    val offset = channel.position()
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    var size = 0L
                                    child.open().use { input ->
                                        while (true) {
                                            if (cancelled) error("Conversion cancelled.")
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            if (n == 0) continue
                                            writeBytes(buffer, n)
                                            digest.update(buffer, 0, n)
                                            size += n
                                            bytesWritten += n
                                        }
                                    }
                                    index.put(JSONObject().put("path", childPath).put("type", "file")
                                        .put("offset", offset).put("size", size)
                                        .put("sha256", digest.digest().joinToString("") { "%02x".format(it) }))
                                    entries++
                                    files++
                                    update(childPath)
                                }
                            }
                        }

                        addDirectory("ExeFS", exefs)
                        addDirectory("RomFS", romfs)
                        align()
                        val indexOffset = channel.position()
                        val payload = JSONObject().put("format", "EdenPack").put("version", 1)
                            .put("alignment", 4096).put("entries", index)
                            .toString().toByteArray(Charsets.UTF_8)
                        writeBytes(payload)
                        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                        header.put("EDENPK01".toByteArray(Charsets.US_ASCII))
                        header.putInt(1).putInt(0).putLong(indexOffset)
                            .putLong(payload.size.toLong()).putLong(entries)
                        header.flip()
                        channel.position(0)
                        while (header.hasRemaining()) channel.write(header)
                        channel.force(true)
                    }
                }
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.progress = 1000
                    status.text = "Conversion complete. Single-pass archive created."
                    chooseButton.isEnabled = true
                    convertButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Conversion failed: " + (e.message ?: e.javaClass.simpleName)
                    chooseButton.isEnabled = true
                    convertButton.isEnabled = true
                }
            }
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var n = value.toDouble()
        var i = -1
        do { n /= 1024.0; i++ } while (n >= 1024.0 && i < units.lastIndex)
        return "%.2f %s".format(n, units[i])
    }

    override fun onDestroy() {
        cancelled = true
        executor.shutdownNow()
        super.onDestroy()
    }
}
