package com.easydora.authservice.service;

import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.dto.SignupResponse;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class UserService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }
    
    public SignupResponse registerUser(SignupRequest signupRequest) {
        // Verificar se email já existe
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            throw new RuntimeException("Email already registered");
        }
        
        // Validar e converter role
        UserRole role = validateAndConvertRole(signupRequest.getRole());
        
        // Criar e salvar usuário
        User user = createUserFromRequest(signupRequest, role);
        User savedUser = userRepository.save(user);
        
        return mapToSignupResponse(savedUser);
    }
    
    private UserRole validateAndConvertRole(String roleString) {
        try {
            return UserRole.valueOf(roleString.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UserRole.BUYER; // Default se role inválido
        }
    }
    
    private User createUserFromRequest(SignupRequest request, UserRole role) {
        User user = new User();
        user.setEmail(request.getEmail().toLowerCase().trim());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setRole(role);
        user.setStatus(UserStatus.PENDING);
        user.setEmailVerified(false);
        
        return user;
    }
    
    private SignupResponse mapToSignupResponse(User user) {
        SignupResponse response = new SignupResponse();
        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setRole(user.getRole().name());
        response.setStatus(user.getStatus().name());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }
    
    // Método adicional para buscar usuário (útil para o login futuro)
    public Optional<User> findUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }
    
    public boolean validateUserCredentials(String email, String password) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        
        User user = userOpt.get();
        return passwordEncoder.matches(password, user.getPasswordHash());
    }
}