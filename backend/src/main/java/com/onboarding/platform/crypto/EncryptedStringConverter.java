package com.onboarding.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Transparent at-rest encryption for a String column: {@code @Convert(converter = ...)}. */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}
