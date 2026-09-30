package com.onboarding.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Stores a date (e.g. date of birth) encrypted, as ISO text inside the ciphertext. */
@Component
@Converter
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

    private final FieldEncryptor encryptor;

    public EncryptedLocalDateConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : encryptor.encrypt(attribute.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        String plain = encryptor.decrypt(dbData);
        return plain == null ? null : LocalDate.parse(plain);
    }
}
