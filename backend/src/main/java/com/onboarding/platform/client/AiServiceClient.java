package com.onboarding.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.exception.AiServiceUnavailableException;
import com.onboarding.platform.exception.DocumentUnreadableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Thin wrapper around the Python FastAPI service (/extract/passport, /extract/cin).
 * Blocks on the response: the backend is servlet-based, and the caller needs the
 * extraction result before it can answer the upload request anyway.
 */
@Component
public class AiServiceClient {

    private final WebClient webClient;

    public AiServiceClient(@Qualifier("aiServiceWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public JsonNode extract(DocumentType type, DocumentSide side, byte[] image, String filename, String contentType) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", new ByteArrayResource(image) {
                    @Override
                    public String getFilename() {
                        // FastAPI's UploadFile requires a filename on the part.
                        return filename != null ? filename : "upload";
                    }
                })
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM);

        try {
            return webClient.post()
                    .uri(pathFor(type, side))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
                throw new DocumentUnreadableException(detailFrom(e));
            }
            throw new AiServiceUnavailableException("AI service returned " + e.getStatusCode().value(), e);
        } catch (WebClientRequestException e) {
            throw new AiServiceUnavailableException("AI service is unreachable", e);
        } catch (RuntimeException e) {
            // block() wraps timeouts (and other reactive errors) in a plain RuntimeException.
            throw new AiServiceUnavailableException("AI service call failed: " + e.getMessage(), e);
        }
    }

    private static String pathFor(DocumentType type, DocumentSide side) {
        return switch (type) {
            case PASSPORT -> "/extract/passport";
            case CIN -> side == DocumentSide.BACK ? "/extract/cin/back" : "/extract/cin";
        };
    }

    /** FastAPI puts the human-readable reason in {"detail": "..."}; surface it to the user. */
    private static String detailFrom(WebClientResponseException e) {
        try {
            JsonNode json = e.getResponseBodyAs(JsonNode.class);
            if (json != null && json.hasNonNull("detail") && json.get("detail").isTextual()) {
                return json.get("detail").asText();
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic message
        }
        return "The document could not be read. Please retake the photo.";
    }
}
