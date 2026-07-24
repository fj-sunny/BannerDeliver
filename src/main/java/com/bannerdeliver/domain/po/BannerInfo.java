package com.bannerdeliver.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;

/**
 * Banner 基础配置，对应 banner_info 表。
 *
 * <p>数据库中的时间字段是 VARCHAR(19)，因此这里有意使用 String，
 * 不做 LocalDateTime 的隐式转换。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "banner_info", autoResultMap = true)
public class BannerInfo {

    @TableId(value = "banner_id", type = IdType.AUTO)
    private Long bannerId;

    private Long productId;

    private String beginTime;

    private String endTime;

    private String url;

    /** 状态：0-停用，1-启用。 */
    @TableField(value = "status", jdbcType = JdbcType.TINYINT)
    private Integer status;

    private String createTime;

    private String updateTime;
}
