package dev.eden.edenpackconverter

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var source: Uri? = null
    private var sourceLabel = "No folder selected"
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseButton: Button
    private lateinit var convertButton: Button
    private var cancelled = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source = uri
            sourceLabel = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            status.text = "Source: $sourceLabel"
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
        val title = TextView(this).apply {
            text = "EdenPack Converter"
            textSize = 26f
        }
        val subtitle = TextView(this).apply {
            text = "Convert an extracted game folder containing ExeFS/ and RomFS/ into an uncompressed EdenPack v1 archive. Files are streamed; the complete game is never loaded into RAM."
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

    private data class Item(val path: String, val file: DocumentFile?, val directory: Boolean, val size: Long)

    private fun collect(root: DocumentFile): List<Item> {
        val result = mutableListOf<Item>()
        val names = root.listFiles().mapNotNull { it.name }.toSet()
        require("ExeFS" in names && "RomFS" in names) { "Selected folder must directly contain ExeFS/ and RomFS/." }
        result += Item("ExeFS", null, true, 0)
        result += Item("RomFS", null, true, 0)
        fun walk(dir: DocumentFile, prefix: String) {
            val children = dir.listFiles().sortedBy { it.name ?: "" }
            for (child in children) {
                val name = child.name ?: error("A source item has no filename.")
                require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
                    "Unsupported filename: $name"
                }
                val rel = "$prefix/$name"
                if (child.isDirectory) {
                    result += Item(rel, null, true, 0)
                    walk(child, rel)
                } else {
                    require(child.isFile) { "Unsupported source item: $rel" }
                    result += Item(rel, child, false, child.length().coerceAtLeast(0))
                }
            }
        }
        for (top in listOf("ExeFS", "RomFS")) {
            val dir = root.findFile(top) ?: error("Missing $top/")
            require(dir.isDirectory) { "$top must be a directory." }
            walk(dir, top)
        }
        return result.sortedBy { it.path.toByteArray(Charsets.UTF_8).toList().joinToString("") { b -> "%02x".format(b) } }
    }

    private fun startConversion(outputUri: Uri) {
        val sourceUri = source ?: return
        cancelled = false
        chooseButton.isEnabled = false
        convertButton.isEnabled = false
        status.text = "Scanning source folder…"
        progress.progress = 0
        executor.execute {
            try {
                val root = DocumentFile.fromTreeUri(this, sourceUri) ?: error("Cannot open selected folder.")
                val items = collect(root)
                val total = items.sumOf { it.size }
                val index = JSONArray()
                val pfd = contentResolver.openFileDescriptor(outputUri, "rwt") ?: error("Output provider did not provide a writable file.")
                pfd.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { fos ->
                        val channel = fos.channel
                        fun write(bytes: ByteArray) {
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) channel.write(buffer)
                        }
                        write(ByteArray(32))
                        var completed = 0L
                        val buffer = ByteArray(1024 * 1024)
                        for (item in items) {
                            if (cancelled) error("Conversion cancelled.")
                            val entry = JSONObject().put("path", item.path)
                            if (item.directory) {
                                entry.put("type", "directory")
                            } else {
                                val aligned = (channel.position() + 4095L) and -4096L
                                while (channel.position() < aligned) write(byteArrayOf(0))
                                val offset = channel.position()
                                val digest = MessageDigest.getInstance("SHA-256")
                                var copied = 0L
                                val input = contentResolver.openInputStream(item.file!!.uri) ?: error("Cannot read ${item.path}")
                                input.use { stream ->
                                    while (true) {
                                        if (cancelled) error("Conversion cancelled.")
                                        val count = stream.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        write(buffer.copyOf(count))
                                        digest.update(buffer, 0, count)
                                        copied += count
                                        completed += count
                                        val pct = if (total > 0) (completed * 1000 / total).toInt().coerceIn(0, 1000) else 0
                                        runOnUiThread {
                                            progress.progress = pct
                                            status.text = "Packing ${item.path}\n${formatBytes(completed)} / ${formatBytes(total)}"
                                        }
                                    }
                                }
                                entry.put("type", "file").put("offset", offset).put("size", copied)
                                    .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            }
                            index.put(entry)
                        }
                        val indexOffset = (channel.position() + 4095L) and -4096L
                        while (channel.position() < indexOffset) write(byteArrayOf(0))
                        val payload = JSONObject()
                            .put("format", "EdenPack")
                            .put("version", 1)
                            .put("alignment", 4096)
                            .put("entries", index)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                        write(payload)
                        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                        header.put("EDENPK01".toByteArray(Charsets.US_ASCII))
                        header.putInt(1).putInt(0).putLong(indexOffset).putLong(payload.size.toLong()).putLong(items.size.toLong())
                        header.flip()
                        channel.position(0)
                        while (header.hasRemaining()) channel.write(header)
                        channel.force(true)
                    }
                }
                runOnUiThread {
                    progress.progress = 1000
                    status.text = "Conversion complete. ${items.count { !it.directory }} files, ${formatBytes(total)}."
                    chooseButton.isEnabled = true
                    convertButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Conversion failed: ${e.message ?: e.javaClass.simpleName}"
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
