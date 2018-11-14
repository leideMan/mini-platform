package com.mnsoft.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mnsoft.auth.model.RevokeToken;
import com.mnsoft.auth.model.RefreshToken;

import java.util.List;

/**
 * Author by hiling, Email admin@mn-soft.com, Date on 10/12/2018.
 */
public interface RefreshTokenService extends IService<RefreshToken> {
    List<String> getRevokeRefreshToken(List<RevokeToken> list);

    void batchDeleteByRefreshToken(List<String> refreshTokenList);

    void deleteExpiredRefreshToken();
}