package com.bannerdeliver.service.impl;

import com.bannerdeliver.entity.BannerInfo;
import com.bannerdeliver.mapper.BannerInfoMapper;
import com.bannerdeliver.service.BannerInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class BannerInfoServiceImpl extends ServiceImpl<BannerInfoMapper, BannerInfo>
        implements BannerInfoService {
}
