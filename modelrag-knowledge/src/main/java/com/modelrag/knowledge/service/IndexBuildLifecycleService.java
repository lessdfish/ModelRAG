package com.modelrag.knowledge.service;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Owns deterministic V2 build state policy and atomic document-pointer activation. */
@Service
public class IndexBuildLifecycleService {
    private final IndexBuildRepository builds;
    private final DocumentRepository documents;
    private final TransactionOperations transactions;

    public IndexBuildLifecycleService(IndexBuildRepository builds, DocumentRepository documents,
            TransactionOperations transactions) {
        this.builds = builds;
        this.documents = documents;
        this.transactions = transactions;
    }

    public IndexBuild createBuild(long datasetId, long documentId, long documentVersionId,
            String embeddingProfile, String rerankProfile, Map<String, Object> metadata) {
        return transactions.execute(status -> {
            Document document = documents.findById(documentId);
            if (document.datasetId() != datasetId || document.activeVersionId() == null
                    || document.activeVersionId() != documentVersionId) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "IndexBuild 必须针对当前活动文档版本");
            }
            return builds.create(datasetId, documentId, documentVersionId,
                    embeddingProfile, rerankProfile, metadata);
        });
    }

    public IndexBuild transition(long buildId, IndexBuildState expected, IndexBuildState next) {
        return transactions.execute(status -> {
            if (!builds.transition(buildId, expected, next)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                        "IndexBuild 状态已被其他执行者改变");
            }
            return builds.findById(buildId).orElseThrow(
                    () -> new BusinessException(ErrorCode.INTERNAL, "状态转移后 IndexBuild 不可见"));
        });
    }

    /** Performs a non-throwing compare-and-set for competing completion workers. */
    public boolean tryTransition(long buildId, IndexBuildState expected, IndexBuildState next) {
        return transactions.execute(status -> builds.transition(buildId, expected, next));
    }

    public IndexBuild updateCounts(long buildId, long nodeCount, long unitCount,
            long vectorCount, long lexicalCount) {
        return transactions.execute(status -> {
            builds.updateCounts(buildId, nodeCount, unitCount, vectorCount, lexicalCount);
            return builds.findById(buildId).orElseThrow(
                    () -> new BusinessException(ErrorCode.INTERNAL, "计数更新后 IndexBuild 不可见"));
        });
    }

    public IndexBuild markReady(long buildId) {
        return transition(buildId, IndexBuildState.VERIFYING, IndexBuildState.READY);
    }

    public void fail(long buildId, String safeError) {
        transactions.executeWithoutResult(status -> builds.markFailed(buildId, safeError));
    }

    /** Atomically switches a READY V2 build while leaving legacy V1 pointers untouched. */
    public IndexBuild activateBuild(long buildId) {
        return transactions.execute(status -> {
            IndexBuild initial = builds.findById(buildId).orElseThrow(
                    () -> new BusinessException(ErrorCode.NOT_FOUND, "IndexBuild 不存在"));
            documents.lockForIndexBuildActivation(initial.documentId());

            Document document = documents.findById(initial.documentId());
            IndexBuild target = builds.findById(buildId).orElseThrow(
                    () -> new BusinessException(ErrorCode.NOT_FOUND, "IndexBuild 不存在"));
            if (target.documentId() != document.id() || target.datasetId() != document.datasetId()
                    || !Objects.equals(target.documentVersionId(), document.activeVersionId())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "过期文档版本的 IndexBuild 不能激活");
            }
            if (target.state() != IndexBuildState.READY) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "只有 READY IndexBuild 可以激活");
            }

            builds.findActiveByDocumentId(document.id()).ifPresent(previous -> {
                if (previous.id() == target.id()) {
                    throw new BusinessException(ErrorCode.VALIDATION, "目标 IndexBuild 已经是 ACTIVE");
                }
                if (!builds.transition(previous.id(), IndexBuildState.ACTIVE, IndexBuildState.SUPERSEDED)) {
                    throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                            "旧 ACTIVE IndexBuild 状态已改变");
                }
            });
            if (!builds.transition(target.id(), IndexBuildState.READY, IndexBuildState.ACTIVE)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                        "目标 IndexBuild 状态已改变");
            }
            documents.activateIndexBuild(document.id(), target.id());
            return builds.findById(target.id()).orElseThrow(
                    () -> new BusinessException(ErrorCode.INTERNAL, "激活后 IndexBuild 不可见"));
        });
    }

    public IndexBuild activate(long buildId) {
        return activateBuild(buildId);
    }
}
