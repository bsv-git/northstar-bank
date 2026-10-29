package com.northstar.identity.service;

import com.northstar.identity.dto.*;

public interface AuthService {
    UserResponse signup(UserSignupRequest request);
}
