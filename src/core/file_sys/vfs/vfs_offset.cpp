// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <atomic>
#include <utility>

#include "common/logging.h"
#include "core/file_sys/vfs/vfs_offset.h"

namespace FileSys {

OffsetVfsFile::OffsetVfsFile(VirtualFile file_, std::size_t size_, std::size_t offset_,
                             std::string name_)
    : file(file_), offset(offset_), size(size_), name(std::move(name_)) {}

OffsetVfsFile::~OffsetVfsFile() = default;

std::string OffsetVfsFile::GetName() const {
    return name.empty() ? file->GetName() : name;
}

std::size_t OffsetVfsFile::GetSize() const {
    return size;
}

bool OffsetVfsFile::Resize(std::size_t new_size) {
    if (offset + new_size < file->GetSize()) {
        size = new_size;
    } else {
        auto res = file->Resize(offset + new_size);
        if (!res)
            return false;
        size = new_size;
    }

    return true;
}

VirtualDir OffsetVfsFile::GetContainingDirectory() const {
    return nullptr;
}

bool OffsetVfsFile::IsWritable() const {
    return file->IsWritable();
}

bool OffsetVfsFile::IsReadable() const {
    return file->IsReadable();
}

std::size_t OffsetVfsFile::Read(u8* data, std::size_t length, std::size_t r_offset) const {
    static std::atomic_size_t trace_count{};
    const auto sequence = trace_count.fetch_add(1, std::memory_order_relaxed);
    const auto bounded = TrimToFit(length, r_offset);
    const auto source_offset = offset + r_offset;
    const auto actual = file->Read(data, bounded, source_offset);
    if (sequence < 512 || actual != bounded) {
        LOG_INFO(Loader,
                 "[EDENPACK_TRACE] layer=romfs_file seq={} name={} logical_offset={} "
                 "logical_size={} source_offset={} requested={} returned={}",
                 sequence, name.empty() ? file->GetName() : name, r_offset, size, source_offset,
                 bounded, actual);
    }
    return actual;
}

std::size_t OffsetVfsFile::Write(const u8* data, std::size_t length, std::size_t r_offset) {
    return file->Write(data, TrimToFit(length, r_offset), offset + r_offset);
}

std::optional<u8> OffsetVfsFile::ReadByte(std::size_t r_offset) const {
    if (r_offset >= size) {
        return std::nullopt;
    }

    return file->ReadByte(offset + r_offset);
}

std::vector<u8> OffsetVfsFile::ReadBytes(std::size_t r_size, std::size_t r_offset) const {
    return file->ReadBytes(TrimToFit(r_size, r_offset), offset + r_offset);
}

std::vector<u8> OffsetVfsFile::ReadAllBytes() const {
    return file->ReadBytes(size, offset);
}

bool OffsetVfsFile::WriteByte(u8 data, std::size_t r_offset) {
    if (r_offset < size)
        return file->WriteByte(data, offset + r_offset);

    return false;
}

std::size_t OffsetVfsFile::WriteBytes(const std::vector<u8>& data, std::size_t r_offset) {
    return file->Write(data.data(), TrimToFit(data.size(), r_offset), offset + r_offset);
}

bool OffsetVfsFile::Rename(std::string_view new_name) {
    return file->Rename(new_name);
}

std::size_t OffsetVfsFile::GetOffset() const {
    return offset;
}

std::size_t OffsetVfsFile::TrimToFit(std::size_t r_size, std::size_t r_offset) const {
    return std::clamp(r_size, std::size_t{0}, size - r_offset);
}

} // namespace FileSys
