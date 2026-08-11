package com.bannerdeliver.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bannerdeliver.config.mybatis.StringBlobTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;

/** Banner 人群包分页数据，对应 banner_crowd 表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "banner_crowd", autoResultMap = true)
public class BannerCrowd {

    /** 人群包分页主键，自增。 */
    @TableId(value = "banner_crowd_id", type = IdType.AUTO)
    private Long bannerCrowdId;

    /** 所属 Banner ID。 */
    private Long bannerId;

    /** 分页序号，从 0 起，同一 Banner 内不可重复。 */
    private Integer pageNum;

    /**
     * 用户 ID 列表，库中为 BLOB。
     * 标准格式 JSON 数组，兼容逗号/换行分隔；由 StringBlobTypeHandler UTF-8 读写。
     */
    @TableField(value = "user_list", jdbcType = JdbcType.BLOB,
            typeHandler = StringBlobTypeHandler.class)
    private String userList;

    /** 创建时间，Unix 毫秒。 */
    private Long createTime;

    /** 最后更新时间，Unix 毫秒。 */
    private Long updateTime;
}
