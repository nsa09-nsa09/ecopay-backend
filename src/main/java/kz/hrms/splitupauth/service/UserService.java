package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserDto getCurrentUser(User user) {
        return userMapper.toDto(user);
    }

    @Transactional
    public UserDto updateProfile(User user, UpdateProfileRequest request) {

        user.setDisplayName(request.getDisplayName());

        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }

//        if (request.getPhoneNumber() != null) {
//            user.setPhoneNumber(request.getPhoneNumber());
//        }

        userRepository.save(user);

        return userMapper.toDto(user);
    }
}