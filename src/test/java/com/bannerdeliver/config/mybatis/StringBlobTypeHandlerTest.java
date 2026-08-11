package com.bannerdeliver.config.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StringBlobTypeHandlerTest {

    private final StringBlobTypeHandler handler = new StringBlobTypeHandler();

    @Test
    void shouldWriteStringAsUtf8Bytes() throws Exception {
        AtomicReference<byte[]> writtenBytes = new AtomicReference<>();
        PreparedStatement statement = proxy(PreparedStatement.class, (method, arguments) -> {
            if ("setBytes".equals(method.getName())) {
                writtenBytes.set((byte[]) arguments[1]);
            }
            return null;
        });

        handler.setNonNullParameter(statement, 1, "用户-1001", JdbcType.BLOB);

        assertThat(writtenBytes.get())
                .isEqualTo("用户-1001".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReadUtf8BytesAsString() throws Exception {
        byte[] storedBytes = "1001,用户-1002".getBytes(StandardCharsets.UTF_8);
        ResultSet resultSet = proxy(ResultSet.class, (method, arguments) -> storedBytes);

        String result = handler.getNullableResult(resultSet, "user_list");

        assertThat(result).isEqualTo("1001,用户-1002");
    }

    @Test
    void shouldKeepSqlNullAsNull() throws Exception {
        ResultSet resultSet = proxy(ResultSet.class, (method, arguments) -> null);

        assertThat(handler.getNullableResult(resultSet, "user_list")).isNull();
    }

    private <T> T proxy(Class<T> interfaceType, Invocation invocation) {
        Object proxy = Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                (ignored, method, arguments) -> invocation.invoke(method, arguments));
        return interfaceType.cast(proxy);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
