package com.htmake.reader.config;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.v3.DatabindCodec;
import io.vertx.core.spi.JsonFactory;
import io.vertx.core.spi.json.JsonCodec;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.module.kotlin.KotlinModule;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Configure JSON before any Vert.x API or storage helper uses it, including outside Spring. */
public final class ReaderJsonFactory implements JsonFactory {
    private static final JsonCodec CODEC = new ReaderJsonCodec();

    @Override
    public int order() {
        return 0;
    }

    @Override
    public JsonCodec codec() {
        return CODEC;
    }

    // Java compilation can resolve Vert.x's Java 21 multi-release classes; Kotlin cannot.
    private static final class ReaderJsonCodec implements JsonCodec {
        // Rebuild Vert.x's immutable mapper to retain its factory, formats and JSON wrappers.
        private final JsonMapper mapper = ((JsonMapper) DatabindCodec.mapper()).rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Legacy imports coerce null primitive fields to zero/false, as Jackson 2 did.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

        @Override
        public <T> T fromString(String json, Class<T> type) {
            return decode(type, () -> mapper.readValue(json, type));
        }

        @Override
        public <T> T fromBuffer(Buffer json, Class<T> type) {
            return decode(type, () -> mapper.readValue(json.getBytes(), type));
        }

        @Override
        public <T> T fromValue(Object json, Class<T> type) {
            return decode(type, () -> mapper.convertValue(json, type));
        }

        @Override
        public String toString(Object value, boolean pretty) {
            return encode(() -> pretty
                ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                : mapper.writeValueAsString(value));
        }

        @Override
        public Buffer toBuffer(Object value, boolean pretty) {
            return encode(() -> Buffer.buffer(pretty
                ? mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)
                : mapper.writeValueAsBytes(value)));
        }

        private <T> T decode(Class<T> type, Supplier<T> read) {
            try {
                T value = read.get();
                if (type != Object.class) return value;
                // Vert.x's untyped API exposes JSON wrappers rather than raw Maps/Lists.
                if (value instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    T adapted = (T) new JsonObject((Map<String, Object>) map);
                    return adapted;
                }
                if (value instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    T adapted = (T) new JsonArray(list);
                    return adapted;
                }
                return value;
            } catch (Exception exception) {
                throw new DecodeException("Failed to decode: " + exception.getMessage(), exception);
            }
        }

        private <T> T encode(Supplier<T> write) {
            try {
                return write.get();
            } catch (Exception exception) {
                throw new EncodeException("Failed to encode as JSON: " + exception.getMessage(), exception);
            }
        }
    }
}
