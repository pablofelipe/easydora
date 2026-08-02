package com.easydora.authservice.service;

import com.easydora.authservice.config.RabbitMQConfig;
import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationContext;
import com.easydora.correlation.OutboxEnvelopeCodec;
import com.easydora.authservice.dto.SignupRequest;
import com.easydora.authservice.dto.SignupResponse;
import com.easydora.authservice.entity.OutboxEvent;
import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;
import com.easydora.authservice.entity.UserStatus;
import com.easydora.authservice.repository.OutboxEventRepository;
import com.easydora.authservice.repository.UserRepository;
import com.easydora.authservice.event.UserRegisteredEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.net.URLEncoder;

@Service
@Transactional
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenService verificationTokenService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, VerificationTokenService verificationTokenService, OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationTokenService = verificationTokenService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }
    
    public SignupResponse registerUser(SignupRequest signupRequest) {
        // Check whether the email already exists
        if (userRepository.existsByEmail(signupRequest.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        // Validate and convert role
        UserRole role = validateAndConvertRole(signupRequest.getRole());

        // Create and save the user
        User user = createUserFromRequest(signupRequest, role);

        String verificationToken = verificationTokenService.generateEmailVerificationToken(user);
        user.setEmailVerificationToken(verificationToken);
        user.setTokenCreatedAt(LocalDateTime.now());

        User savedUser = userRepository.save(user);

        UserRegisteredEvent event = new UserRegisteredEvent(
            savedUser.getId(),
            savedUser.getEmail(),
            savedUser.getFirstName(),
            savedUser.getLastName(),
            signupRequest.getRole(),
            verificationToken
        );
        String eventJson;
        try {
            eventJson = objectMapper.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize UserRegisteredEvent", e);
        }
        String envelopedPayload = OutboxEnvelopeCodec.wrap(
            CorrelationContext.currentOrNewCorrelationId(),
            CorrelationContext.newMessageId(),
            eventJson
        );
        outboxEventRepository.save(new OutboxEvent(
            RabbitMQConfig.EXCHANGE_NAME,
            RabbitMQConfig.USER_REGISTERED_KEY,
            envelopedPayload
        ));
        BusinessEventLog.info(logger, "user.registered.outboxed", savedUser.getId(), "User registered event recorded in outbox");

        return mapToSignupResponse(savedUser, verificationToken);
    }

    // ADMIN is a real UserRole value (used by orders-service's fulfillment
    // authorization), but is never self-service -- the public
    // signup endpoint may only mint BUYER/SELLER accounts. The ADMIN account
    // is provisioned exclusively via a Flyway seed migration.
    private static final Set<String> SELF_SERVICE_ROLES = Set.of("BUYER", "SELLER");

    private UserRole validateAndConvertRole(String roleString) {
        String normalized = roleString.toUpperCase();
        if (!SELF_SERVICE_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid role for signup: " + roleString);
        }
        return UserRole.valueOf(normalized);
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

        String encodedToken = URLEncoder.encode(verificationToken, StandardCharsets.UTF_8);
        response.setVerificationUrl("/auth/verify-email?token=" + encodedToken);

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
        // Validate the JWT token first
        if (!verificationTokenService.validateVerificationToken(token)) {
            throw new RuntimeException("Invalid or expired verification token");
        }

        // Extract the email from the JWT token
        String email = verificationTokenService.getEmailFromToken(token);

        // Look up the user by email
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        // Check whether the token stored in the database matches (optional - extra security layer)
        if (user.getEmailVerificationToken() != null &&
            !user.getEmailVerificationToken().equals(token)) {
            throw new RuntimeException("Token mismatch");
        }

        // Check whether it's already active
        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new RuntimeException("Email already verified");
        }

        // Activate the user
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null); // Remove the token after use
        user.setUpdatedAt(LocalDateTime.now());

        userRepository.save(user);

        String envelopedPayload = OutboxEnvelopeCodec.wrap(
            CorrelationContext.currentOrNewCorrelationId(),
            CorrelationContext.newMessageId(),
            String.valueOf(user.getId())
        );

        outboxEventRepository.save(new OutboxEvent(
            RabbitMQConfig.EXCHANGE_NAME,
            RabbitMQConfig.USER_VERIFIED_KEY,
            envelopedPayload
        ));
        BusinessEventLog.info(logger, "user.verified.outboxed", user.getId(), "User verified event recorded in outbox");
    }
}