package com.bannerdeliver.service.impl;

import com.bannerdeliver.entity.BannerCrowd;
import com.bannerdeliver.mapper.BannerCrowdMapper;
import com.bannerdeliver.service.BannerCrowdService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class BannerCrowdServiceImpl extends ServiceImpl<BannerCrowdMapper, BannerCrowd>
        implements BannerCrowdService {
}
