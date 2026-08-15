package com.contextcompresso.usage;

import com.contextcompresso.config.UsageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Taps a response {@link DataBuffer} flux to recover the provider's `usage` block, without
 * buffering or delaying what is forwarded to the client. Every buffer is passed through
 * untouched via {@code doOnNext}; a bounded side-buffer (capped at
 * {@code maxCapturedResponseBytes}) accumulates a copy of the bytes purely for parsing once
 * the stream completes.
 *
 * <p>Capped rather than unbounded because a streaming response body can be arbitrarily large,
 * but `usage` always appears within the first/last few KB of the events that carry it — there
 * is no reason to hold the whole body in memory just to find it.
 */
@Component
public class UsageCapture {

    private static final Logger log = LoggerFactory.getLogger(UsageCapture.class);

    private final UsageExtractor extractor;
    private final int maxBytes;

    public UsageCapture(UsageExtractor extractor, UsageProperties properties) {
        this.extractor = extractor;
        this.maxBytes = properties.maxCapturedResponseBytes();
    }

    public interface UsageSink {
        void accept(UsageExtractor.ExtractedUsage usage);
    }

    public Flux<DataBuffer> tap(Flux<DataBuffer> body, boolean streaming, UsageSink sink) {
        ByteArrayOutputStream sideBuffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        boolean[] truncated = {false};

        return body.doOnNext(buffer -> {
            if (truncated[0]) {
                return;
            }
            synchronized (sideBuffer) {
                if (sideBuffer.size() >= maxBytes) {
                    truncated[0] = true;
                    return;
                }
                int readable = buffer.readableByteCount();
                byte[] bytes = new byte[readable];
                buffer.asByteBuffer().duplicate().get(bytes);
                int toCopy = Math.min(bytes.length, maxBytes - sideBuffer.size());
                sideBuffer.write(bytes, 0, toCopy);
            }
        }).doOnComplete(() -> {
            try {
                byte[] captured = sideBuffer.toByteArray();
                UsageExtractor.ExtractedUsage usage = streaming
                        ? extractor.fromSseChunk(new String(captured, StandardCharsets.UTF_8))
                        : extractor.fromJsonBody(captured);
                sink.accept(usage);
            } catch (Exception e) {
                log.debug("Usage capture failed, recording nothing (fail-open)", e);
                sink.accept(UsageExtractor.ExtractedUsage.EMPTY);
            }
        }).doOnError(e -> sink.accept(UsageExtractor.ExtractedUsage.EMPTY));
    }
}
