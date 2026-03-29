package com.mmagent.session;

import com.mmagent.document.AgentscopeSessionDocument;
import io.agentscope.core.session.ListHashUtil;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 MongoDB 的 AgentScope Session 实现。
 *
 * <p>存储结构参照 AgentScope MySQL 实现（MysqlSession），消息按行存储到
 * agentscope_sessions 集合，每条消息对应一条文档：
 * <ul>
 *   <li>单值状态：itemIndex = 0
 *   <li>列表状态（消息历史）：itemIndex = 0, 1, 2, ...
 *   <li>列表哈希：stateKey = "{key}:_hash"，itemIndex = 0
 * </ul>
 *
 * <p>save/get 均使用 ReactiveMongoTemplate + .block()，调用方须保证运行在
 * Schedulers.boundedElastic() 线程上，不可在 reactor 调度线程中直接调用。
 */
public class MongoSession implements Session {

    private static final Logger log = LoggerFactory.getLogger(MongoSession.class);
    private static final String HASH_KEY_SUFFIX = ":_hash";
    private static final int SINGLE_STATE_INDEX = 0;
    private static final String COLLECTION = "agentscope_sessions";

    private final ReactiveMongoTemplate mongoTemplate;

    public MongoSession(ReactiveMongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ─────────────────── save 单值 ───────────────────

    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        AgentscopeSessionDocument doc = buildDoc(
                sessionKey.toIdentifier(), key, SINGLE_STATE_INDEX, toJson(value));
        mongoTemplate.save(doc, COLLECTION).block();
    }

    // ─────────────────── save 列表（带哈希变更检测）───────────────────

    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        if (values.isEmpty()) return;
        String sessionId = sessionKey.toIdentifier();
        String hashKey   = key + HASH_KEY_SUFFIX;

        String currentHash  = ListHashUtil.computeHash(values);
        String storedHash   = getStoredHashValue(sessionId, hashKey);
        int    existingCount = getListCount(sessionId, key);

        boolean needsFullRewrite = ListHashUtil.needsFullRewrite(
                currentHash, storedHash, values.size(), existingCount);

        if (needsFullRewrite) {
            // 先删除旧数据，再全量写入（原子性不强，但 Session 不要求事务）
            deleteBySessionAndKey(sessionId, key);
            insertItems(sessionId, key, values, 0);
            saveHashValue(sessionId, hashKey, currentHash);
        } else if (values.size() > existingCount) {
            // 增量追加新消息
            insertItems(sessionId, key, values.subList(existingCount, values.size()), existingCount);
            saveHashValue(sessionId, hashKey, currentHash);
        }
        // else: 无变化，跳过
    }

    // ─────────────────── get 单值 ───────────────────

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String id = makeId(sessionKey.toIdentifier(), key, SINGLE_STATE_INDEX);
        Query q   = Query.query(Criteria.where("_id").is(id));
        AgentscopeSessionDocument doc = mongoTemplate
                .findOne(q, AgentscopeSessionDocument.class, COLLECTION).block();
        if (doc == null) return Optional.empty();
        return Optional.of(fromJson(doc.getStateData(), type));
    }

    // ─────────────────── get 列表 ───────────────────

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        Query q = Query.query(
                Criteria.where("sessionId").is(sessionKey.toIdentifier())
                        .and("stateKey").is(key))
                .with(org.springframework.data.domain.Sort.by("itemIndex"));
        List<AgentscopeSessionDocument> docs = mongoTemplate
                .find(q, AgentscopeSessionDocument.class, COLLECTION).collectList().block();
        if (docs == null) return List.of();
        return docs.stream()
                .map(d -> fromJson(d.getStateData(), itemType))
                .collect(Collectors.toList());
    }

    // ─────────────────── exists ───────────────────

    @Override
    public boolean exists(SessionKey sessionKey) {
        Query q = Query.query(Criteria.where("sessionId").is(sessionKey.toIdentifier())).limit(1);
        Boolean exists = mongoTemplate.exists(q, AgentscopeSessionDocument.class, COLLECTION).block();
        return Boolean.TRUE.equals(exists);
    }

    // ─────────────────── delete ───────────────────

    @Override
    public void delete(SessionKey sessionKey) {
        Query q = Query.query(Criteria.where("sessionId").is(sessionKey.toIdentifier()));
        mongoTemplate.remove(q, AgentscopeSessionDocument.class, COLLECTION).block();
    }

    // ─────────────────── listSessionKeys ───────────────────

    @Override
    public Set<SessionKey> listSessionKeys() {
        List<String> ids = mongoTemplate
                .findDistinct("sessionId", AgentscopeSessionDocument.class, String.class)
                .collectList().block();
        if (ids == null) return Set.of();
        return ids.stream().map(SimpleSessionKey::of).collect(Collectors.toSet());
    }

    // ─────────────────── 内部辅助 ───────────────────

    private String getStoredHashValue(String sessionId, String hashKey) {
        String id = makeId(sessionId, hashKey, SINGLE_STATE_INDEX);
        Query q   = Query.query(Criteria.where("_id").is(id));
        AgentscopeSessionDocument doc = mongoTemplate
                .findOne(q, AgentscopeSessionDocument.class, COLLECTION).block();
        return doc == null ? null : doc.getStateData();
    }

    private void saveHashValue(String sessionId, String hashKey, String hash) {
        AgentscopeSessionDocument doc = buildDoc(sessionId, hashKey, SINGLE_STATE_INDEX, hash);
        mongoTemplate.save(doc, COLLECTION).block();
    }

    private int getListCount(String sessionId, String key) {
        Query q = Query.query(
                Criteria.where("sessionId").is(sessionId).and("stateKey").is(key));
        Long count = mongoTemplate.count(q, AgentscopeSessionDocument.class, COLLECTION).block();
        return count == null ? 0 : count.intValue();
    }

    private void deleteBySessionAndKey(String sessionId, String key) {
        Query q = Query.query(
                Criteria.where("sessionId").is(sessionId).and("stateKey").is(key));
        mongoTemplate.remove(q, AgentscopeSessionDocument.class, COLLECTION).block();
    }

    private void insertItems(String sessionId, String key, List<? extends State> items, int startIndex) {
        Instant now = Instant.now();
        List<AgentscopeSessionDocument> docs = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            int idx = startIndex + i;
            docs.add(AgentscopeSessionDocument.builder()
                    .id(makeId(sessionId, key, idx))
                    .sessionId(sessionId)
                    .stateKey(key)
                    .itemIndex(idx)
                    .stateData(toJson(items.get(i)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        mongoTemplate.insertAll(docs).collectList().block();
    }

    private AgentscopeSessionDocument buildDoc(String sessionId, String key, int idx, String data) {
        Instant now = Instant.now();
        return AgentscopeSessionDocument.builder()
                .id(makeId(sessionId, key, idx))
                .sessionId(sessionId)
                .stateKey(key)
                .itemIndex(idx)
                .stateData(data)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String makeId(String sessionId, String key, int idx) {
        return sessionId + ":" + key + ":" + idx;
    }

    private static String toJson(State value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    private static <T> T fromJson(String json, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(json, type);
    }
}
