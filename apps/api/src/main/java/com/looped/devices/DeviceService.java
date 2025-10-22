package com.looped.devices;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class DeviceService {
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    public DeviceService(DeviceRepository deviceRepository, UserRepository userRepository) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
    }

    public Result register(String firebaseUid, String apnsToken, String platform) {
        var userOpt = userRepository.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) {
            return Result.userNotProvisioned();
        }
        long userId = userOpt.get().id;
        var upsert = deviceRepository.upsert(userId, apnsToken, platform);
        return Result.ok(upsert.id(), upsert.created());
    }

    public record Result(Status status, Long id, boolean created) {
        static Result ok(long id, boolean created) { return new Result(Status.OK, id, created); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, null, false); }
    }

    public enum Status { OK, USER_NOT_PROVISIONED }
}

