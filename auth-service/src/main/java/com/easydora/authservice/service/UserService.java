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

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class UserService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RabbitMQProducerService rabbitMQProducerService;
    private final VerificationTokenService verificationTokenService;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, RabbitMQProducerService rabbitMQProducerService, VerificationTokenService verificationTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rabbitMQProducerService = rabbitMQProducerService;
        this.verificationTokenService = verificationTokenService;
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

        String verificationToken = verificationTokenService.generateEmailVerificationToken(user);
        user.setEmailVerificationToken(verificationToken);
        user.setTokenCreatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);

        rabbitMQProducerService.sendUserRegisteredEvent(
            savedUser.getId(),
            savedUser.getEmail(),
            savedUser.getFirstName(),
            savedUser.getLastName(),
            verificationToken
        );

        return mapToSignupResponse(savedUser, verificationToken);
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
    
    private SignupResponse mapToSignupResponse(User user, String verificationToken) {
        SignupResponse response = new SignupResponse();
        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setRole(user.getRole().name());
        response.setStatus(user.getStatus().name());
        response.setCreatedAt(user.getCreatedAt());
        response.setVerificationToken(verificationToken);
        response.setVerificationUrl("/auth/verify-email?token=" + verificationToken);
        return response;
    }

    public Optional<User> findActiveUserByEmail(String email) {
        return userRepository.findActiveUserByEmail(email);
    }
    
    public boolean validateUserCredentials(String email, String password) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }
        
        User user = userOpt.get();
        return passwordEncoder.matches(password, user.getPasswordHash());
    }

    public void verifyEmail(String token) {
        // Validar o token JWT primeiro
        if (!verificationTokenService.validateVerificationToken(token)) {
            throw new RuntimeException("Invalid or expired verification token");
        }
        
        // Extrair o email do token JWT
        String email = verificationTokenService.getEmailFromToken(token);
        
        // Buscar usuário pelo email
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Verificar se o token armazenado no banco corresponde (opcional - camada extra de segurança)
        if (user.getEmailVerificationToken() != null && 
            !user.getEmailVerificationToken().equals(token)) {
            throw new RuntimeException("Token mismatch");
        }
        
        // Verificar se já não está ativo
        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new RuntimeException("Email already verified");
        }
        
        // Ativar o usuário
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null); // Remove o token após uso
        user.setUpdatedAt(LocalDateTime.now());
        
        userRepository.save(user);
    }
}