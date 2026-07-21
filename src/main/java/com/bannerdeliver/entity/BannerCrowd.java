package com.bannerdeliver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bannerdeliver.infrastructure.mybatis.StringBlobTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;

/**
 * Banner 人群包分页数据，对应 banner_crowd 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "banner_crowd", autoResultMap = true)
public class BannerCrowd {

    @TableId(value = "banner_crowd_id", type = IdType.AUTO)
    private Long bannerCrowdId;

    private Long bannerId;

    private Integer pageNum;

    /**
     * 数据库列是 BLOB，Java 属性按业务约定保持 String；
     * StringBlobTypeHandler 统一使用 UTF-8 读写。
     */
    @TableField(value = "user_list", jdbcType = JdbcType.BLOB,
            typeHandler = StringBlobTypeHandler.class)
    private String userList;

    private String createTime;

    private String updateTime;
}
