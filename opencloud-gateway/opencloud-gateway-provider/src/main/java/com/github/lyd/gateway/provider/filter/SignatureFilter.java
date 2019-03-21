package com.github.lyd.gateway.provider.filter;

import com.alibaba.fastjson.JSONObject;
import com.github.lyd.base.client.model.entity.BaseApp;
import com.github.lyd.common.constants.CommonConstants;
import com.github.lyd.common.exception.OpenSignatureDeniedHandler;
import com.github.lyd.common.exception.OpenSignatureException;
import com.github.lyd.common.exception.SignatureDeniedHandler;
import com.github.lyd.common.model.ResultBody;
import com.github.lyd.common.security.OpenAuthUser;
import com.github.lyd.common.security.OpenHelper;
import com.github.lyd.common.utils.SignatureUtils;
import com.github.lyd.common.utils.WebUtils;
import com.github.lyd.gateway.provider.configuration.ApiGatewayProperties;
import com.github.lyd.gateway.provider.service.feign.BaseAppRemoteService;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * 数字签名验证过滤器,认证完成之后执行
 *
 * @author: liuyadu
 * @date: 2018/11/28 18:26
 * @description:
 */
public class SignatureFilter implements Filter {
    private SignatureDeniedHandler signatureDeniedHandler;
    private BaseAppRemoteService systemAppClient;
    private ApiGatewayProperties apiGatewayProperties;
    /**
     * 忽略签名
     */
    private final static List<RequestMatcher> NOT_SIGN = getIgnoreMatchers(
            "/**/login/**",
            "/**/logout/**"
    );

    public SignatureFilter(BaseAppRemoteService systemAppClient, ApiGatewayProperties apiGatewayProperties) {
        this.systemAppClient = systemAppClient;
        this.apiGatewayProperties = apiGatewayProperties;
        this.signatureDeniedHandler = new OpenSignatureDeniedHandler();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    protected static List<RequestMatcher> getIgnoreMatchers(String... antPatterns) {
        List<RequestMatcher> matchers = Lists.newArrayList();
        for (String path : antPatterns) {
            matchers.add(new AntPathRequestMatcher(path));
        }
        return matchers;
    }

    protected boolean notSign(HttpServletRequest request) {
        for (RequestMatcher match : NOT_SIGN) {
            if (match.matches(request)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取请求Body
     *
     * @param request
     * @return
     */
    public String getBodyString(final ServletRequest request) {
        StringBuilder sb = new StringBuilder();
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            inputStream = cloneInputStream(request.getInputStream());
            reader = new BufferedReader(new InputStreamReader(inputStream, Charset.forName("UTF-8")));
            String line = "";
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return sb.toString();
    }

    /**
     * 复制输入流
     *
     * @param inputStream
     * @return</br>
     */
    public InputStream cloneInputStream(ServletInputStream inputStream) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        try {
            while ((len = inputStream.read(buffer)) > -1) {
                byteArrayOutputStream.write(buffer, 0, len);
            }
            byteArrayOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        InputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
        return byteArrayInputStream;
    }


    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        OpenAuthUser auth = OpenHelper.getAuthUser();
        if (isAuthenticated() && apiGatewayProperties.getCheckSign() && !notSign(request)) {
            try {
                //开始验证签名
                String appId = auth.getAuthAppId();
                if (systemAppClient != null && appId != null) {
                    String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
                    Map params = Maps.newHashMap();
                    if (MediaType.APPLICATION_JSON_VALUE.equals(contentType) || MediaType.APPLICATION_JSON_UTF8_VALUE.equals(contentType)) {
                        // json类型参数
                        String body = getBodyString(request);
                        params = JSONObject.parseObject(body, Map.class);
                    } else {
                        // 普通表单请求参数
                        params = WebUtils.getParameterMap(request);
                    }
                    // 验证请求参数
                    SignatureUtils.validateParams(params);
                    // 获取客户端信息
                    ResultBody<BaseApp> result = systemAppClient.getApp(appId);
                    BaseApp app = result.getData();
                    if (app == null) {
                        throw new OpenSignatureException("clientId无效");
                    }
                    // 强制覆盖请求参数clientId
                    params.put(CommonConstants.SIGN_CLIENT_ID_KEY, app.getAppId());
                    // 服务器验证签名结果
                    if (!SignatureUtils.validateSign(params, app.getAppSecret())) {
                        throw new OpenSignatureException("签名验证失败!");
                    }
                }
            } catch (Exception ex) {
                signatureDeniedHandler.handle(request, response, ex);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return true;
    }

    @Override
    public void destroy() {

    }

}
