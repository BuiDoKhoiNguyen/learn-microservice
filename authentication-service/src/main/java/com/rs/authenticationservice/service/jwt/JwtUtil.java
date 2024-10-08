package com.rs.authenticationservice.service.jwt;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.rs.authenticationservice.dto.request.RefreshRequest;
import com.rs.authenticationservice.dto.response.AuthResponse;
import com.rs.authenticationservice.entity.InvalidatedToken;
import com.rs.authenticationservice.entity.User;
import com.rs.authenticationservice.repository.InvalidatedTokenRepository;
import com.rs.authenticationservice.service.impl.CustomUserDetailsService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import lombok.experimental.NonFinal;

@Service
public class JwtUtil {

    @NonFinal
    @Value("${jwt.secret}")
    protected String SECRET_KEY;

    @NonFinal
    @Value("${jwt.expiration}")
    protected long EXPIRATION;

    @NonFinal
    @Value("${jwt.refresh-expiration}")
    protected long REFRESH_EXPIRATION;

    private final CustomUserDetailsService customUserDetailsService;
    private final InvalidatedTokenRepository invalidatedTokenRepository;

    @Autowired
    public JwtUtil(
            CustomUserDetailsService customUserDetailsService, InvalidatedTokenRepository invalidatedTokenRepository) {
        this.customUserDetailsService = customUserDetailsService;
        this.invalidatedTokenRepository = invalidatedTokenRepository;
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();

        return Jwts.builder()
                .setClaims(claims)
                .setHeaderParam("typ", "JWT")
                .setId(UUID.randomUUID().toString())
                .setSubject(userDetails.getUsername())
                .setIssuer("Bui Do Khoi Nguyen")
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .claim("scope", buildScope(customUserDetailsService.convertToUser(userDetails)))
                .signWith(SignatureAlgorithm.HS256, SECRET_KEY)
                .compact();
    }

    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        String scope = (String) claims.get("scope");
        return Arrays.asList(scope.split(" "));
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(SECRET_KEY).parseClaimsJws(token).getBody();
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    public AuthResponse refreshToken(RefreshRequest request) {
        Claims claims = verifyToken(request.getToken(), true);

        String jti = claims.getId();
        Date expiryTime = claims.getExpiration();

        InvalidatedToken invalidatedToken =
                InvalidatedToken.builder().id(jti).expiryTime(expiryTime).build();

        invalidatedTokenRepository.save(invalidatedToken);
        String username = claims.getSubject();
        UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);
        String token = generateToken(userDetails);

        return AuthResponse.builder()
                .jwt(token)
                .expiryTime(extractExpiration(token))
                .build();
    }

    public Claims verifyToken(String token, boolean isRefresh) {
        try {
            Claims claims = extractAllClaims(token);
            Date expiration = claims.getExpiration();
            Date issueDate = claims.getIssuedAt();
            Date now = new Date();

            if (isRefresh) {
                Date refreshExpiration = new Date(issueDate.getTime() + REFRESH_EXPIRATION);
                if (now.after(refreshExpiration)) {
                    throw new JwtException("Token refresh period has expired");
                }
            } else {
                if (now.after(expiration)) {
                    throw new JwtException("Token has expired");
                }
            }

            String jti = claims.getId();
            if (invalidatedTokenRepository.existsById(jti)) {
                throw new JwtException("Token is invalidated");
            }

            return claims;
        } catch (ExpiredJwtException | SignatureException e) {
            throw new JwtException("Invalid JWT token", e);
        }
    }

    private String buildScope(User user) {
        StringJoiner joiner = new StringJoiner(" ");

        if (user.getRoles() != null) {
            user.getRoles().forEach(role -> {
                joiner.add("ROLE_" + role.getName());
                if (role.getPermissions() != null) {
                    role.getPermissions().forEach(permission -> joiner.add(permission.getName()));
                }
            });
        }
        return joiner.toString();
    }
}
