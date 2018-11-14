package com.mnsoft.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mnsoft.auth.constant.ErrorMessage;
import com.mnsoft.auth.constant.GrantType;
import com.mnsoft.auth.constant.RedisNamespaces;
import com.mnsoft.auth.listener.AccessTokenRevokeThread;
import com.mnsoft.auth.listener.RefreshTokenRevokeThread;
import com.mnsoft.auth.mapper.AccessTokenMapper;
import com.mnsoft.auth.mapper.RefreshTokenMapper;
import com.mnsoft.auth.model.*;
import com.mnsoft.auth.service.ClientService;
import com.mnsoft.auth.service.AccessTokenService;
import com.mnsoft.auth.service.UserService;
import com.mnsoft.common.exception.BusinessException;
import com.mnsoft.common.utils.UuidUtils;
import com.mnsoft.common.utils.jwtUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Author by hiling, Email admin@mn-soft.com, Date on 10/9/2018.
 */
@Service
public class AccessTokenServiceImpl extends ServiceImpl<AccessTokenMapper,AccessToken> implements AccessTokenService {

    @Resource
    private AccessTokenMapper accessTokenMapper;

    @Resource
    private RefreshTokenMapper refreshTokenMapper;

    @Autowired
    private ClientService clientService;

    @Autowired
    private UserService userService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * accessToken过期时间（秒），绝对过期，默认2小时
     */
    @Value("${oauth.access-token.expiration:7200}")
    Integer accessTokenExpiration;

    /**
     * 滑动过期时间（秒），默认24小时
     */
    @Value("${oauth.refresh-token.sliding-expiration:86400}")
    Integer refreshTokenSlidingExpiration;

    /**
     * 绝对过期时间（秒） ,默认7天
     */
    @Value("${oauth.refresh-token.absolute-expiration:604800}")
    Integer refreshTokenAbsoluteExpiration;


    public String getJwtToken(String accessToken) {
        if (StringUtils.isNotEmpty(accessToken)){
            String jwtToken = stringRedisTemplate.opsForValue().get(RedisNamespaces.ACCESS_TOKEN+accessToken);
            if (StringUtils.isNotEmpty(jwtToken)){
                return jwtToken;
            }
        }
        //如果缓存中没有，说明该Token已过期或错误，然后直接返回null，避免大量查询DB导致DB压力过大
        return null;
    }

    public AccessToken createAccessToken(String clientId,String clientSecret, String grantType, String username, String password, String refreshToken) {

        GrantType type;
        if (grantType.equals(GrantType.PASSWORD.toString())
                || grantType.equals(GrantType.REFRESH_TOKEN.toString())
                || grantType.equals(GrantType.CLIENT_CREDENTIALS.toString())) {
            type = GrantType.typeOf(grantType);
        } else {
            throw new BusinessException(ErrorMessage.TOKEN_GRANT_TYPE_NOT_SUPPORTED);
        }

        //验证Client&Secret是否正确
        Client client = clientService.getOne(new QueryWrapper<Client>().lambda()
                .eq(Client::getClientId, clientId)
                .eq(Client::getClientSecret, clientSecret));

        if (client == null) {
            throw new BusinessException(ErrorMessage.TOKEN_CLIENT_ERROR);
        }

        AccessToken token = new AccessToken();
        LocalDateTime now = LocalDateTime.now();

        //refresh_token模式
        if (type == GrantType.REFRESH_TOKEN) {

            if (StringUtils.isEmpty(refreshToken)) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_ERROR);
            }

            //获取该refresh token的信息
            RefreshToken refreshTokenFromDB = refreshTokenMapper.selectOne(
                    new QueryWrapper<RefreshToken>().lambda()
                            .eq(RefreshToken::getRefreshToken, refreshToken));

            if (refreshTokenFromDB == null) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_ERROR);
            }

            if (!StringUtils.equals(refreshTokenFromDB.getClientId(), clientId)) {
                throw new BusinessException(ErrorMessage.TOKEN_REFRESH_CLIENT_ID_NOT_MATCH);
            }

            //判断refresh token是否过期
            //绝对过期
            if (refreshTokenAbsoluteExpiration > 0) {
                if (refreshTokenFromDB.getCreateTime().plusSeconds(refreshTokenAbsoluteExpiration).isBefore(now)) {
                    throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_EXPIRATION);
                }
            }

            //滑动过期
            if (refreshTokenSlidingExpiration > 0) {
                if (refreshTokenFromDB.getLastUsedTime().plusSeconds(refreshTokenSlidingExpiration).isBefore(now)) {
                    throw new BusinessException(ErrorMessage.TOKEN_REFRESH_TOKEN_EXPIRATION);
                }
            }
            //使用refresh token模式时，需更新oauth_refresh_token表中的last_used_time字段
            refreshTokenMapper.updateById(new RefreshToken().setId(refreshTokenFromDB.getId()).setLastUsedTime(now));

            username = refreshTokenFromDB.getUserId();

        } else if (type == GrantType.PASSWORD) {
            //password模式，需要验证用户名密码，且生成refresh token
            Account account = userService.getByAccount(username, password);
            if (account == null) {
                throw new BusinessException(ErrorMessage.TOKEN_USER_ERROR);
            }

            refreshToken = UuidUtils.getUUID();
        } else {
            //客户端模式时，不需要userId和refresh token。
            username = null;
            refreshToken = null;
        }

        //创建Access Token(单机器的UUID的TPS在10万级别，随机数产生依赖与unix的/dev/random文件，因此增加线程不能提高生成效率)
        String accessToken = UuidUtils.getUUID();

        //生成jwtToken
        String jwtToken = jwtUtils.createJavaWebToken("oauth", username, clientId, now.plusSeconds(accessTokenExpiration), now);

        token.setClientId(clientId);
        token.setUserId(username);
        token.setAccessToken(accessToken);
        token.setJwtToken(jwtToken);
        token.setRefreshToken(refreshToken);
        token.setExpiresIn(accessTokenExpiration);
        token.setCreateTime(now);

        int accessTokenId = accessTokenMapper.insert(token);
        if (accessTokenId > 0) {

            //password模式时，需添加refreshToken到oauth_refresh_token表
            if (type == GrantType.PASSWORD) {
                refreshTokenMapper.insert(
                        new RefreshToken()
                                .setClientId(token.getClientId())
                                .setUserId(token.getUserId())
                                .setRefreshToken(refreshToken)
                                .setExpiresIn(refreshTokenSlidingExpiration > 0 ? refreshTokenSlidingExpiration : refreshTokenAbsoluteExpiration)
                                .setCreateTime(now)
                                .setLastUsedTime(now));

                //添加该授权信息插入到过期队列，有清除线程清除当前时间前的授权信息
                RefreshTokenRevokeThread.addRefreshTokenToRevokeQueue(clientId, username, now);
            }

            //缓存到Redis oat=OAuth2 Access Token / ort=OAuth2 Refresh Token
            stringRedisTemplate.opsForValue().set(RedisNamespaces.ACCESS_TOKEN + accessToken, jwtToken, accessTokenExpiration, TimeUnit.SECONDS);

            //添加该授权信息插入到过期队列，有清除线程清除当前时间前的授权信息
            AccessTokenRevokeThread.addAccessTokenToRevokeQueue(clientId, username, now);

            return token;
        }
        return null;
    }

    public boolean deleteToken(String accessToken) {
        //移除缓存
        stringRedisTemplate.delete(RedisNamespaces.ACCESS_TOKEN + accessToken);

        //移除DB
        String refreshToken = accessTokenMapper.getRefreshToken(accessToken);
        if (StringUtils.isNotEmpty(refreshToken)) {
            accessTokenMapper.batchDeleteByAccessToken(Arrays.asList(accessToken));
            refreshTokenMapper.batchDeleteByRefreshToken(Arrays.asList(refreshToken));
        }
        return true;
    }

    public List<String> getRevokeAccessToken(List<RevokeToken> list){
        if (list.isEmpty()){
            return Arrays.asList();
        }
        return accessTokenMapper.getRevokeAccessToken(list);
    }

    public void deleteExpiredAccessToken(){
        accessTokenMapper.deleteExpiredAccessToken();
    }

    public void batchDeleteByAccessToken(List<String> accessTokenList) {
        if (accessTokenList.isEmpty()){
            return;
        }
        accessTokenMapper.batchDeleteByAccessToken(accessTokenList);
    }
}