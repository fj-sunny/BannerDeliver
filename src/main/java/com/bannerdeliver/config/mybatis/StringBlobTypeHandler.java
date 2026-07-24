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

    @Override
    public void setNonNullParameter(PreparedStatement ps, int index, String parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setBytes(index, parameter.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decode(rs.getBytes(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decode(rs.getBytes(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decode(cs.getBytes(columnIndex));
    }

    private String decode(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
