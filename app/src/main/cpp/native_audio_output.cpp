#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace {

// One playback-thread producer and one realtime callback consumer. Neither PCM writes nor
// callbacks take locks, allocate memory, call Java, or wait for the other thread.
class NativeOutput final : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback,
                           public std::enable_shared_from_this<NativeOutput> {
public:
    NativeOutput(int rate, int channels, bool floating, bool openSles, int device,
                 int usage, int contentType)
            : rate_(rate), channels_(channels), floating_(floating), openSles_(openSles),
              device_(device), usage_(usage), contentType_(contentType),
              bytesPerFrame_(channels * (floating ? 4 : 2)),
              capacityFrames_(std::max(1, rate / 4)),
              ring_(static_cast<size_t>(capacityFrames_) * bytesPerFrame_) {}

    oboe::Result open() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setAudioApi(openSles_ ? oboe::AudioApi::OpenSLES : oboe::AudioApi::AAudio)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSampleRate(rate_)
                ->setChannelCount(channels_)
                ->setFormat(floating_ ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
                ->setUsage(static_cast<oboe::Usage>(usage_))
                ->setContentType(static_cast<oboe::ContentType>(contentType_))
                ->setSessionId(oboe::SessionId::None)
                ->setDeviceId(device_)
                ->setFormatConversionAllowed(true)
                ->setChannelConversionAllowed(true)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(shared_from_this()))
                ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(shared_from_this()));
        const auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            error_.store(static_cast<int>(result));
            close();
            return result;
        }
        if (stream_->getSampleRate() != rate_ || stream_->getChannelCount() != channels_ ||
            stream_->getFormat() != (floating_ ? oboe::AudioFormat::Float : oboe::AudioFormat::I16) ||
            stream_->getAudioApi() != (openSles_ ? oboe::AudioApi::OpenSLES : oboe::AudioApi::AAudio)) {
            error_.store(static_cast<int>(oboe::Result::ErrorInvalidFormat));
            close();
            return oboe::Result::ErrorInvalidFormat;
        }
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        return oboe::Result::OK;
    }

    void close() {
        // Moving the stream breaks the callback ownership cycle. Oboe close joins the data
        // callback before its buffers are destroyed; shared callbacks cover error-thread lifetime.
        auto stream = std::move(stream_);
        if (stream) {
            stream->requestStop();
            stream->close();
        }
    }

    int write(const uint8_t *data, int length) {
        const auto error = error_.load(std::memory_order_acquire);
        if (error != 0) return error;
        if (!stream_) return static_cast<int>(oboe::Result::ErrorClosed);
        const int64_t write = writeFrame_.load(std::memory_order_relaxed);
        const int64_t read = readFrame_.load(std::memory_order_acquire);
        const int frames = static_cast<int>(std::min<int64_t>(length / bytesPerFrame_,
                                                             capacityFrames_ - (write - read)));
        const int start = static_cast<int>(write % capacityFrames_);
        const int first = std::min(frames, capacityFrames_ - start);
        std::memcpy(ring_.data() + static_cast<size_t>(start) * bytesPerFrame_, data,
                    static_cast<size_t>(first) * bytesPerFrame_);
        std::memcpy(ring_.data(), data + static_cast<size_t>(first) * bytesPerFrame_,
                    static_cast<size_t>(frames - first) * bytesPerFrame_);
        writeFrame_.store(write + frames, std::memory_order_release);
        return frames * bytesPerFrame_;
    }

    void play() {
        draining_.store(false, std::memory_order_release);
        if (stream_) recordResult(stream_->requestStart());
    }

    void pause() {
        if (stream_) recordResult(stream_->pause());
    }

    void flush() {
        close();
        readFrame_.store(0);
        writeFrame_.store(0);
        timelineCount_.store(0);
        underruns_.store(0);
        error_.store(0);
        draining_.store(false);
        lastPosition_ = 0;
        callbackFrames_ = 0;
        underflow_ = false;
        open();
    }

    void drain() {
        // Continue rendering until the device has presented the final queued frame. Returning
        // Stop from the last data callback would discard frames still queued in the native API.
        draining_.store(true, std::memory_order_release);
    }

    void setVolume(float volume) { volume_.store(volume, std::memory_order_relaxed); }
    int error() const { return error_.load(std::memory_order_acquire); }
    int underrunCount() const { return underruns_.load(std::memory_order_relaxed); }

    int64_t positionFrames() {
        if (!stream_) return lastPosition_;
        // getFramesRead is the output device's presentation counter, not callback delivery time.
        // OpenSL ES exposes its native millisecond clock here; AAudio uses its frame counter.
        const int64_t head = stream_->getFramesRead();
        const int64_t count = timelineCount_.load(std::memory_order_acquire);
        int64_t position = 0;
        const int64_t oldest = std::max<int64_t>(0, count - kTimelineCapacity);
        for (int64_t index = count - 1; index >= oldest; --index) {
            const auto &segment = timeline_[index % kTimelineCapacity];
            const int64_t revision = segment.revision.load(std::memory_order_acquire);
            if (revision != index * 2 + 2) continue;
            const int64_t streamStart = segment.streamStart.load(std::memory_order_relaxed);
            const int64_t mediaStart = segment.mediaStart.load(std::memory_order_relaxed);
            const int64_t frames = segment.mediaFrames.load(std::memory_order_relaxed);
            if (segment.revision.load(std::memory_order_acquire) != revision) continue;
            if (head >= streamStart) {
                position = mediaStart + std::min(head - streamStart, frames);
                break;
            }
            position = mediaStart;
        }
        lastPosition_ = std::max(lastPosition_, std::min(position, writeFrame_.load(std::memory_order_acquire)));
        return lastPosition_;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
                                         int32_t numFrames) override {
        const int64_t read = readFrame_.load(std::memory_order_relaxed);
        const int64_t write = writeFrame_.load(std::memory_order_acquire);
        const int frames = static_cast<int>(std::min<int64_t>(numFrames, write - read));
        const float volume = volume_.load(std::memory_order_relaxed);
        auto *destination = static_cast<uint8_t *>(audioData);
        int remaining = frames;
        int copied = 0;
        while (remaining > 0) {
            const int start = static_cast<int>((read + copied) % capacityFrames_);
            const int block = std::min(remaining, capacityFrames_ - start);
            const auto *source = ring_.data() + static_cast<size_t>(start) * bytesPerFrame_;
            auto *target = destination + static_cast<size_t>(copied) * bytesPerFrame_;
            if (volume == 1.0f) {
                std::memcpy(target, source, static_cast<size_t>(block) * bytesPerFrame_);
            } else if (floating_) {
                const auto *input = reinterpret_cast<const float *>(source);
                auto *output = reinterpret_cast<float *>(target);
                for (int i = 0; i < block * channels_; ++i) output[i] = input[i] * volume;
            } else {
                const auto *input = reinterpret_cast<const int16_t *>(source);
                auto *output = reinterpret_cast<int16_t *>(target);
                for (int i = 0; i < block * channels_; ++i) {
                    output[i] = static_cast<int16_t>(std::clamp(
                            std::lrintf(input[i] * volume), -32768L, 32767L));
                }
            }
            copied += block;
            remaining -= block;
        }
        std::memset(destination + static_cast<size_t>(frames) * bytesPerFrame_, 0,
                    static_cast<size_t>(numFrames - frames) * bytesPerFrame_);

        // Associate each device callback with the real media frames it carries. Inserted underrun
        // silence never advances the media clock or makes DefaultAudioSink finish too early.
        const int64_t index = timelineCount_.load(std::memory_order_relaxed);
        auto &segment = timeline_[index % kTimelineCapacity];
        segment.revision.store(index * 2 + 1, std::memory_order_release);
        // A resampling Oboe stream can invoke several source callbacks inside one device
        // callback, before its frames-written counter changes. Count source frames ourselves.
        segment.streamStart.store(callbackFrames_, std::memory_order_relaxed);
        segment.mediaStart.store(read, std::memory_order_relaxed);
        segment.mediaFrames.store(frames, std::memory_order_relaxed);
        segment.revision.store(index * 2 + 2, std::memory_order_release);
        timelineCount_.store(index + 1, std::memory_order_release);
        callbackFrames_ += numFrames;
        readFrame_.store(read + frames, std::memory_order_release);

        const bool underflow = frames < numFrames && write > 0 &&
                               !draining_.load(std::memory_order_acquire);
        if (underflow && !underflow_) underruns_.fetch_add(1, std::memory_order_relaxed);
        underflow_ = underflow;
        return oboe::DataCallbackResult::Continue;
    }

    bool onError(oboe::AudioStream *, oboe::Result error) override {
        error_.store(static_cast<int>(error), std::memory_order_release);
        // The playback thread owns stop/close. Suppress Oboe's independent automatic close so
        // normal Media3 recovery cannot race another close of the same stream.
        return true;
    }

private:
    void recordResult(oboe::Result result) {
        if (result != oboe::Result::OK) error_.store(static_cast<int>(result), std::memory_order_release);
    }

    struct Segment {
        std::atomic<int64_t> revision{-1};
        std::atomic<int64_t> streamStart{0};
        std::atomic<int64_t> mediaStart{0};
        std::atomic<int64_t> mediaFrames{0};
    };
    static constexpr int64_t kTimelineCapacity = 1024;
    const int rate_, channels_;
    const bool floating_, openSles_;
    const int device_, usage_, contentType_, bytesPerFrame_, capacityFrames_;
    std::vector<uint8_t> ring_;
    std::array<Segment, kTimelineCapacity> timeline_;
    std::atomic<int64_t> readFrame_{0}, writeFrame_{0}, timelineCount_{0};
    std::atomic<int> error_{0}, underruns_{0};
    std::atomic<float> volume_{1.0f};
    std::atomic<bool> draining_{false};
    bool underflow_ = false;
    int64_t lastPosition_ = 0;
    int64_t callbackFrames_ = 0;
    std::shared_ptr<oboe::AudioStream> stream_;
};

using Handle = std::shared_ptr<NativeOutput>;
Handle &output(jlong handle) { return *reinterpret_cast<Handle *>(handle); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_create(
        JNIEnv *, jobject, jint sampleRate, jint channels, jboolean floating, jboolean openSles,
        jint device, jint usage, jint contentType) {
    if (sampleRate <= 0 || channels < 1 || channels > 2) return 0;
    auto native = std::make_shared<NativeOutput>(sampleRate, channels, floating, openSles,
                                               device, usage, contentType);
    if (native->open() != oboe::Result::OK) return 0;
    return reinterpret_cast<jlong>(new Handle(std::move(native)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_write(
        JNIEnv *env, jobject, jlong handle, jobject buffer, jint offset, jint length) {
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!data || offset < 0 || length < 0 || static_cast<int64_t>(offset) + length > capacity) {
        return static_cast<int>(oboe::Result::ErrorIllegalArgument);
    }
    return output(handle)->write(data + offset, length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_play(JNIEnv *, jobject, jlong handle) {
    output(handle)->play();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_pause(JNIEnv *, jobject, jlong handle) {
    output(handle)->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_flush(JNIEnv *, jobject, jlong handle) {
    output(handle)->flush();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_drain(JNIEnv *, jobject, jlong handle) {
    output(handle)->drain();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_release(JNIEnv *, jobject, jlong handle) {
    auto *native = reinterpret_cast<Handle *>(handle);
    (*native)->close();
    delete native;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_setVolume(JNIEnv *, jobject, jlong handle, jfloat volume) {
    output(handle)->setVolume(volume);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_positionFrames(JNIEnv *, jobject, jlong handle) {
    return output(handle)->positionFrames();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_underrunCount(JNIEnv *, jobject, jlong handle) {
    return output(handle)->underrunCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ljyh_mei_playback_NativeAudioBridge_error(JNIEnv *, jobject, jlong handle) {
    return output(handle)->error();
}
