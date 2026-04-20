package kz.hrms.splitupauth.security;

public interface FieldEncryptionService {
    String encrypt(String rawValue);
    String decrypt(String encryptedValue);
}