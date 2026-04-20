package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .role(user.getRole())
                .reputation(user.getReputation())
                .build();
    }
}