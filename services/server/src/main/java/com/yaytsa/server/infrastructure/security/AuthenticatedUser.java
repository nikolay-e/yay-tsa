package com.yaytsa.server.infrastructure.security;

import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AuthenticatedUser implements UserDetails {

  private final UserEntity userEntity;
  private final String token;
  private final String deviceId;

  public AuthenticatedUser(UserEntity userEntity, String token, String deviceId) {
    this.userEntity = userEntity;
    this.token = token;
    this.deviceId = deviceId;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    if (userEntity.isAdmin()) {
      return List.of(
          new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Override
  public String getPassword() {
    return userEntity.getPasswordHash();
  }

  @Override
  public String getUsername() {
    return userEntity.getUsername();
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return userEntity.isActive();
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return userEntity.isActive();
  }

  public UserEntity getUserEntity() {
    return userEntity;
  }

  public String getToken() {
    return token;
  }

  public String getDeviceId() {
    return deviceId;
  }
}
