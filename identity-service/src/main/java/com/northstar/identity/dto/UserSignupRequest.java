package com.northstar.identity.dto;

import jakarta.validation.constraints.*;

public record UserSignupRequest(
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(min = 8, max = 72) String confirmPassword) {
}
