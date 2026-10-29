package com.northstar.identity.service;

import com.northstar.identity.domain.User;
import com.northstar.identity.dto.*;
import com.northstar.identity.exception.*;
import com.northstar.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository users;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse signup(UserSignupRequest request) {
        var email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!request.password().equals(request.confirmPassword())) {
            throw new PasswordMismatchException();
        }
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UserNotFoundException(email));
        if (user.isRegistered() || user.getPasswordHash() != null) {
            throw new UserAlreadyExistsException(email);
        }
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRegistered(true);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException(email);
        }
        return new UserResponse(user.getId(), user.getEmail(), "Account activated successfully");
    }
}
