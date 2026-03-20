package com.mmagent.repository;

import com.mmagent.document.SessionDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;

public interface SessionRepository extends ReactiveMongoRepository<SessionDocument, String> {

    Flux<SessionDocument> findByStatus(String status);

    Flux<SessionDocument> findByLastActiveAtBefore(Instant threshold);
}
