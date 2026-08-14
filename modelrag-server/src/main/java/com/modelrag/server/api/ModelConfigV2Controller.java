package com.modelrag.server.api;

import com.modelrag.api.ModelConfigRequest;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.server.model.UserModelConfigStore;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v2/model-configs")
public class ModelConfigV2Controller {
    private final UserModelConfigStore configs;
    private final AccessControlService access;

    public ModelConfigV2Controller(UserModelConfigStore configs, AccessControlService access) {
        this.configs = configs;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<UserModelConfigStore.Config>> list() {
        return ApiResponse.success(configs.list(access.currentUser().id()));
    }

    @PostMapping
    public ApiResponse<UserModelConfigStore.Config> save(@Valid @RequestBody ModelConfigRequest request) {
        String userId = access.currentUser().id();
        return ApiResponse.success(configs.save(userId, request));
    }

    @PostMapping("/{configId}/validate")
    public ApiResponse<UserModelConfigStore.Validation> validate(@PathVariable String configId) {
        String userId = access.currentUser().id();
        return ApiResponse.success(configs.validate(userId, configId));
    }

    @DeleteMapping("/{configId}")
    public ApiResponse<Void> delete(@PathVariable String configId) {
        configs.revoke(access.currentUser().id(), configId);
        return ApiResponse.success(null);
    }
}
