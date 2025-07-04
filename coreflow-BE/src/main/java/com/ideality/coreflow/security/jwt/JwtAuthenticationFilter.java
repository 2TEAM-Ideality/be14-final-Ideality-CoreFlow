package com.ideality.coreflow.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideality.coreflow.auth.command.application.dto.RequestLogin;
import com.ideality.coreflow.auth.command.application.dto.RequestResetPassword;
import com.ideality.coreflow.auth.command.application.dto.RequestResetPasswordVerify;
import com.ideality.coreflow.auth.command.application.dto.RequestTokenReissue;
import com.ideality.coreflow.common.exception.BaseException;
import com.ideality.coreflow.common.exception.ErrorCode;
import com.ideality.coreflow.common.response.APIResponse;
import com.ideality.coreflow.infra.redis.util.RedisUtil;
import com.ideality.coreflow.infra.tenant.config.TenantContext;
import com.ideality.coreflow.security.custom.CachedBodyHttpServletRequest;
import com.ideality.coreflow.tenant.command.application.dto.RequestCreateTenant;
import com.ideality.coreflow.tenant.command.application.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;
    private final TenantService tenantService;
    private final RedisUtil redisUtil;

    private static final Set<String> CUSTOM_SCHEMA_URIS = Set.of(
            "/api/auth/login",
            "/api/auth/reissue",
            "/api/auth/reset-password/request",
            "/api/auth/reset-password/verify",
            "/api/health"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        log.info("Request 콘텐트타입: " + request.getContentType());
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if ("Post".equalsIgnoreCase(method) && CUSTOM_SCHEMA_URIS.contains(uri)) {
            handleSchemaInitialization(request, response, filterChain, uri);
        } else if ("GET".equalsIgnoreCase(method) && CUSTOM_SCHEMA_URIS.contains(uri)) {
            handleSchemaInitialization(request, response, filterChain, uri);
        } else {
            handleJwtAuthentication(request, response, filterChain);
        }
    }

    private void handleJwtAuthentication(HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String bearerToken = request.getHeader("Authorization");
            String token = "";
            // 토큰 존재 여부 확인
            if (bearerToken == null) {
                token = request.getParameter("token");
                log.info("파라미터 토큰: {}", token);
                if(token == null) {
                    sendErrorResponse(response, "토큰이 존재하지 않습니다.");
                    return;
                }
            } else {
                if (!bearerToken.startsWith("Bearer ")) {
                    sendErrorResponse(response, "토큰 형식이 잘못되었습니다.");
                    return;
                }
                token = bearerToken.substring(7);
            }

            log.info("token {}", token);
            // 토큰 유효성 검증
            if (!jwtUtil.validateAccessToken(token)) {
                sendErrorResponse(response, "유효하지 않은 토큰입니다.");
                return;
            }
            // 블랙리스트 검사
            String blacklistKey = "Blacklist:" + token;
            if (redisUtil.hasKey(blacklistKey)) {
                log.warn("Access Token이 blacklist에 있음 - 인증 중단");
                sendErrorResponse(response, "블랙리스트에 등록된 토큰입니다.");
                return;
            }

            // jwt에서 정보 추출
            String id = jwtProvider.getUserId(token);
            String employeeNum = jwtProvider.getEmployeeNum(token);
            String companySchema = jwtProvider.getCompanySchema(token);

            log.info("jwt 필터에서 테넌트 설정");
            // 테넌트 설정
            TenantContext.setTenant(companySchema);
            if (request.getRequestURI().startsWith("/api/tenant")) {
                if (!"master".equals(companySchema)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
            log.info("테넌트 설정 완료: {}", TenantContext.getTenant());

            // 인증 객체 생성
            List<GrantedAuthority> authorities;
            if (!"master".equals(companySchema)) {
                authorities = jwtProvider.getRoles(token)
                        .stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
            } else {
                authorities = null;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            id,
                            null,
                            authorities);

            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.info("JWT 인증 성공 - employeeNum: {}", employeeNum);

            filterChain.doFilter(request, response);
        } finally {
            // 스레드 로컬 클리어
            TenantContext.clear();
        }
    }

    private void handleSchemaInitialization(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain,
                                            String uri)
            throws IOException, ServletException {

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        try {
            switch (uri) {
                case "/api/auth/login" -> {
                    RequestLogin requestLogin = parseRequest(cachedRequest, RequestLogin.class);
                    TenantContext.setTenant(tenantService.findSchemaNameByCompanyCode(requestLogin.getCompanyCode()));
                }
                case "/api/auth/reissue" -> {
                    RequestTokenReissue requestTokenReissue = parseRequest(cachedRequest, RequestTokenReissue.class);
                    TenantContext.setTenant(requestTokenReissue.getCompanySchema());
                }
                case "/api/auth/reset-password/request" -> {
                    RequestResetPassword requestResetPassword = parseRequest(cachedRequest, RequestResetPassword.class);
                    TenantContext.setTenant(tenantService.findSchemaNameByCompanyCode(requestResetPassword.getCompanyCode()));
                }
                case "/api/auth/reset-password/verify" -> {
                    RequestResetPasswordVerify requestResetPasswordVerify = parseRequest(cachedRequest, RequestResetPasswordVerify.class);
                    TenantContext.setTenant(tenantService.findSchemaNameByCompanyCode(requestResetPasswordVerify.getCompanyCode()));
                }
                case "/api/health" -> {

                }
            }
            filterChain.doFilter(cachedRequest, response);
        } catch (Exception e) {
            throw new BaseException(ErrorCode.INVALID_TENANT);
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T parseRequest(HttpServletRequest request, Class<T> clazz) {
        try {
            return objectMapper.readValue(request.getInputStream(), clazz);
        } catch (IOException e) {
            throw new BaseException(ErrorCode.INVALID_JSON_FORMAT);
        }
    }

    private void sendErrorResponse(HttpServletResponse response, String message) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            APIResponse<?> errorResponse = APIResponse.error(message);
            ObjectMapper mapper = new ObjectMapper();
            String responseBody = mapper.writeValueAsString(errorResponse);
            response.getWriter().write(responseBody);
        } catch (IOException e) {
            log.error("에러 응답 작성 중 실패", e);
        }
    }
}
