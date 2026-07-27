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
 * 时间字段在库中为 VARCHAR(19)，Java 侧保持 String 不做隐式转换。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "banner_info", autoResultMap = true)
public class BannerInfo {

    /** Banner 主键，自增。 */
    @TableId(value = "banner_id", type = IdType.AUTO)
    private Long bannerId;

    /** 所属商品 ID。 */
    private Long productId;

    /** 投放开始时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String beginTime;

    /** 投放结束时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String endTime;

    /** 图片/跳转 URL。 */
    private String url;

    /** 状态：0-停用，1-启用。 */
    @TableField(value = "status", jdbcType = JdbcType.TINYINT)
    private Integer status;

    /** 创建时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String createTime;

    /** 最后更新时间，格式 yyyy-MM-dd HH:mm:ss；对账和幂等比较的依据。 */
    private String updateTime;
}
