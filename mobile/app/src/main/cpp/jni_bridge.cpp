#include "native_sender.h"

#include <jni.h>
#include <srt.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<local_sender::NativeSender>> senders;
jlong next_handle = 1;

std::shared_ptr<local_sender::NativeSender> find_sender(jlong handle) {
    std::lock_guard lock(registry_mutex);
    const auto entry = senders.find(handle);
    return entry == senders.end() ? nullptr : entry->second;
}

std::string copy_utf8(JNIEnv* environment, jstring value, std::size_t maximum_bytes) {
    if (value == nullptr) {
        return {};
    }
    const jsize utf8_size = environment->GetStringUTFLength(value);
    if (utf8_size <= 0 || static_cast<std::size_t>(utf8_size) > maximum_bytes) {
        return {};
    }
    const char* characters = environment->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) {
        return {};
    }
    std::string output(characters);
    environment->ReleaseStringUTFChars(value, characters);
    return output;
}

std::vector<std::uint8_t> copy_bytes(JNIEnv* environment, jbyteArray value, std::size_t maximum_bytes) {
    if (value == nullptr) {
        return {};
    }
    const jsize size = environment->GetArrayLength(value);
    if (size <= 0 || static_cast<std::size_t>(size) > maximum_bytes) {
        return {};
    }
    std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
    environment->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(output.data()));
    if (environment->ExceptionCheck() == JNI_TRUE) {
        std::fill(output.begin(), output.end(), 0);
        return {};
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return srt_startup() == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    std::unordered_map<jlong, std::shared_ptr<local_sender::NativeSender>> owned;
    {
        std::lock_guard lock(registry_mutex);
        owned.swap(senders);
    }
    for (auto& entry : owned) {
        entry.second->stop();
    }
    srt_cleanup();
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeCreate(
    JNIEnv* environment,
    jobject,
    jstring host,
    jstring device_name,
    jint port,
    jbyteArray secret,
    jint latency_milliseconds,
    jint video_codec,
    jboolean audio_enabled,
    jint audio_sample_rate,
    jint audio_channels) {
    local_sender::SenderConfig config;
    config.host = copy_utf8(environment, host, 15);
    config.device_name = copy_utf8(environment, device_name, 48);
    config.port = port >= 0 && port <= 65'535 ? static_cast<std::uint16_t>(port) : 0;
    std::vector<std::uint8_t> secret_bytes = copy_bytes(environment, secret, config.secret.size());
    if (secret_bytes.size() == config.secret.size()) {
        std::copy(secret_bytes.begin(), secret_bytes.end(), config.secret.begin());
    }
    std::fill(secret_bytes.begin(), secret_bytes.end(), 0);
    config.latency_milliseconds = latency_milliseconds;
    config.video_codec = video_codec == 1 ? local_sender::VideoCodec::kHevc : local_sender::VideoCodec::kAvc;
    config.audio_enabled = audio_enabled == JNI_TRUE;
    config.audio_sample_rate = audio_sample_rate;
    config.audio_channels = audio_channels;
    if ((video_codec != 1 && video_codec != 2) || !local_sender::NativeSender::validate_config(config)) {
        std::fill(config.secret.begin(), config.secret.end(), 0);
        return 0;
    }

    auto sender = std::make_shared<local_sender::NativeSender>(std::move(config));
    std::lock_guard lock(registry_mutex);
    const jlong handle = next_handle++;
    senders.emplace(handle, std::move(sender));
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeStart(JNIEnv*, jobject, jlong handle) {
    const auto sender = find_sender(handle);
    return sender != nullptr && sender->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeStop(JNIEnv*, jobject, jlong handle) {
    const auto sender = find_sender(handle);
    if (sender != nullptr) {
        sender->stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<local_sender::NativeSender> sender;
    {
        std::lock_guard lock(registry_mutex);
        const auto entry = senders.find(handle);
        if (entry == senders.end()) {
            return;
        }
        sender = std::move(entry->second);
        senders.erase(entry);
    }
    sender->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeWriteVideo(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jbyteArray access_unit,
    jlong presentation_time_microseconds,
    jboolean key_frame) {
    const auto sender = find_sender(handle);
    std::vector<std::uint8_t> bytes = copy_bytes(
        environment,
        access_unit,
        local_sender::MpegTsMuxer::kMaximumVideoAccessUnitBytes);
    return sender != nullptr && !bytes.empty() &&
            sender->write_video(bytes, presentation_time_microseconds, key_frame == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeWriteAudio(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jbyteArray access_unit,
    jlong presentation_time_microseconds) {
    const auto sender = find_sender(handle);
    std::vector<std::uint8_t> bytes = copy_bytes(
        environment,
        access_unit,
        local_sender::MpegTsMuxer::kMaximumAudioAccessUnitBytes);
    return sender != nullptr && !bytes.empty() && sender->write_audio(bytes, presentation_time_microseconds)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeStatus(JNIEnv*, jobject, jlong handle) {
    const auto sender = find_sender(handle);
    return sender == nullptr ? static_cast<jint>(local_sender::SenderStatus::kStopped)
                             : static_cast<jint>(sender->status());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeError(JNIEnv*, jobject, jlong handle) {
    const auto sender = find_sender(handle);
    return sender == nullptr ? static_cast<jint>(local_sender::SenderError::kInvalidConfiguration)
                             : static_cast<jint>(sender->error());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_localstream_sender_transport_NativeTransport_nativeQueuePercent(JNIEnv*, jobject, jlong handle) {
    const auto sender = find_sender(handle);
    return sender == nullptr ? 0 : sender->queue_percent();
}

#ifdef LOCAL_SENDER_ENABLE_TEST_SEAM
extern "C" JNIEXPORT jint JNICALL
Java_dev_localstream_sender_transport_NativeTestSeam_run(JNIEnv*, jobject) {
    int failures = 0;
    local_sender::BoundedPacketQueue queue(2, 32);
    local_sender::PacketBatch first_batch;
    first_batch.emplace_back(8, 1);
    first_batch.emplace_back(8, 2);
    if (!queue.push_batch(std::move(first_batch)) || queue.size_bytes() != 16 || queue.high_water_bytes() != 16) {
        failures |= 1;
    }
    local_sender::PacketBatch overflow_batch;
    overflow_batch.emplace_back(1, 3);
    if (queue.push_batch(std::move(overflow_batch))) {
        failures |= 2;
    }
    local_sender::Packet packet;
    if (!queue.wait_pop(packet) || packet.size() != 8) {
        failures |= 4;
    }
    queue.cancel();
    while (queue.wait_pop(packet)) {
        // A cancelled queue may drain already committed packets, but must then terminate.
    }

    local_sender::MpegTsMuxer muxer(local_sender::VideoCodec::kAvc, true, 48'000, 1);
    muxer.reset(1'000'000);
    const std::array<std::uint8_t, 7> video{0, 0, 0, 1, 0x65, 1, 2};
    local_sender::PacketBatch transport_packets;
    if (!muxer.write_video(video, 1'000'000, true, transport_packets) || transport_packets.empty()) {
        failures |= 8;
    }
    for (const auto& transport_packet : transport_packets) {
        if (transport_packet.empty() || transport_packet.size() > local_sender::MpegTsMuxer::kSrtPayloadBytes ||
            transport_packet.size() % local_sender::MpegTsMuxer::kTransportPacketBytes != 0) {
            failures |= 16;
        }
    }
    const std::array<std::uint8_t, 4> audio{1, 2, 3, 4};
    transport_packets.clear();
    if (!muxer.write_audio(audio, 1'010'000, transport_packets) || transport_packets.empty()) {
        failures |= 32;
    }

    local_sender::SenderConfig config;
    config.host = "192.168.1.2";
    config.device_name = "Test Android phone";
    config.port = 9'000;
    config.secret.fill(7);
    config.latency_milliseconds = 120;
    if (!local_sender::NativeSender::validate_config(config)) {
        failures |= 64;
    }
    config.host = "8.8.8.8";
    if (local_sender::NativeSender::validate_config(config)) {
        failures |= 128;
    }
    config.secret.fill(0);
    return failures;
}
#endif
