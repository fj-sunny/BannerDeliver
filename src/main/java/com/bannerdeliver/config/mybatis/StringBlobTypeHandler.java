package com.bannerdeliver.config.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 在 MySQL BLOB 和 Java String 之间按 UTF-8 做确定性的双向转换。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.BLOB)
public class StringBlobTypeHandler extends BaseTypeHandler<String> {

    /** 将 Java String 按 UTF-8 编码写入 PreparedStatement 的 BLOB 参数。 */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int index, String parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setBytes(index, parameter.getBytes(StandardCharsets.UTF_8));
    }

    /** 按列名从 ResultSet 读取 BLOB 并解码为 String。 */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decode(rs.getBytes(columnName));
    }

    /** 按列索引从 ResultSet 读取 BLOB 并解码为 String。 */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decode(rs.getBytes(columnIndex));
    }

    /** 从 CallableStatement 读取 BLOB 并解码为 String。 */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decode(cs.getBytes(columnIndex));
    }

    /** 将字节数组按 UTF-8 解码为 String，null 输入返回 null。 */
    private String decode(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
