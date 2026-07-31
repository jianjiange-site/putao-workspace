# User Service 业务流程详解

> 这是代码学习主文档。每条流程都按“入口 → 校验 → service → manager/mapper → DB/Redis → 事务与失败 → 返回”展开。
>
> 代码基线：`dating-server/user-service`、`proto/user/user.proto`、`V1__init_user_tables.sql`。仓库内没有 user-service 测试、定时任务、消息生产者或消费者。

## 一、统一入口与分层

所有业务 RPC 先进入 `UserGrpcService`。它本身只做 mixin 转发：

```text
UserGrpcService
├─ UserIdentityGrpcImpl → UserIdentityService / UserBanService
└─ UserProfileGrpcImpl  → UserProfileService / UserInterestService / UserAvatarService
```

稳定调用方向是：

```text
gRPC entry
→ DTO normalization / validation
→ Service implementation
→ Manager
→ MyBatis Mapper / Redis
→ PostgreSQL / Redis
→ VO
→ Proto builder
→ StreamObserver
```

`UserIdContextInterceptor` 在入口读取三项 metadata：

```java
Context.current()
    .withValue(USER_ID_CONTEXT_KEY, userId)
    .withValue(DEVICE_ID_CONTEXT_KEY, deviceId)
    .withValue(TRACE_ID_CONTEXT_KEY, traceId);
```

- `x-user-id` 解析失败时变成 null；
- `x-device-id` 只透传，当前业务未使用；
- `x-trace-id` 缺失时生成 UUID，但代码没有把它写入 MDC；
- 资料写接口以 context 中的 userId 为权限依据，不信任请求体单独声明的 userId。

### 1.1 异常转换的实际路径

大多数 mixin 方法自行 catch `BizException` 并调用 `UserGrpcService.sendBizError`：

```java
if (ex instanceof UserNotFoundException) {
    status = Status.NOT_FOUND;
} else if (ex instanceof UserBannedException) {
    status = Status.PERMISSION_DENIED;
} else {
    status = Status.INVALID_ARGUMENT;
}
```

因此绝大多数普通业务异常、FORBIDDEN 和内部错误都会被映射成 `INVALID_ARGUMENT`。`GrpcExceptionAdvice` 中定义的 `FAILED_PRECONDITION`/`INTERNAL` 只处理逃出方法的异常，不能把它当成所有接口的统一真实行为。

## 二、接口与维护流程清单

| 编号 | Proto RPC / 流程 | 入口实现 | 状态 |
|---|---|---|---|
| F001 | `ResolveOrCreateByPhone` | `UserIdentityGrpcImpl` | 已实现 |
| F002 | `ResolveOrCreateByThirdParty` | `UserIdentityGrpcImpl` | 已实现 |
| F003 | `ResolveOrCreateByDevice` | `UserIdentityGrpcImpl` | 已实现 |
| F004 | `CheckBan` | `UserIdentityGrpcImpl` | 部分实现 |
| F005 | `GetProfile` | `UserProfileGrpcImpl` | 已实现，头像缺失 |
| F006 | `BatchGetProfile` | `UserProfileGrpcImpl` | 已实现 |
| F007 | `GetUserProfile` | `UserProfileGrpcImpl` | 兼容接口，已实现 |
| F008 | `UpdateUserProfile` | `UserProfileGrpcImpl` | 兼容接口，服务内已实现 |
| F009 | `BatchGetUserProfiles` | `UserProfileGrpcImpl` | 兼容接口，已实现 |
| F010 | `UpdateProfile` | `UserProfileGrpcImpl` | 已实现 |
| F011 | `UpsertOnboarding` | `UserProfileGrpcImpl` | 已实现，但非一次性 |
| F012 | `ReplaceUserInterests` | `UserProfileGrpcImpl` | 已实现 |
| F013 | `PresignAvatarUpload` | `UserProfileGrpcImpl` | 占位实现 |
| F014 | `ConfirmAvatarUpload` | `UserProfileGrpcImpl` | 占位实现 |
| F015 | `GetUserType` | 未覆写 | 未实现，返回 UNIMPLEMENTED |
| F016 | `ListDhCandidates` | 未覆写 | 未实现，返回 UNIMPLEMENTED |
| F017 | `NearbyUsers` | 未覆写 | 未实现，返回 UNIMPLEMENTED |
| M001 | 服务启动与迁移 | Spring Boot / Flyway / 配置类 | 已配置 |
| M002 | 定时任务 | 无 | 不存在 |
| M003 | MQ 生产/消费 | 无 | 不存在 |

## 三、身份解析生命周期

### F001 手机号解析或创建

#### 业务目标与触发

上游已完成短信验证，user-service 负责把手机号解析成稳定 userId；首次出现时创建 placeholder。

#### 完整调用链

```text
UserGrpcService.resolveOrCreateByPhone
→ UserIdentityGrpcImpl.resolveOrCreateByPhone
→ UserIdentityServiceImpl.resolveOrCreateByPhone
→ normalizePhone / requireAppName
→ RedissonClient.getLock + RLock.tryLock
→ UserLoginPhoneManager.findByPhoneAndApp
→ UserLoginPhoneMapper.findByPhoneAndApp
→ 命中: UserInfoManager.touchLastOpenAt + findByUserId
→ 未命中: createPlaceholder
   → SnowflakeIdGenerator.nextId
   → UserInfoManager.insertPlaceholder
   → UserInfoMapper.insert
→ UserLoginPhoneManager.insert
→ UserLoginPhoneMapper.insert
→ ResolveOrCreateVO → ResolveOrCreateResponse
```

#### 分步执行

1. **入口检查手机号。**  
   `UserIdentityGrpcImpl` 只检查非空；空值直接发送 BAD_REQUEST。App 枚举没有在入口拒绝未指定值，而是使用 `request.getAppName().name()` 转成字符串。

2. **二次规范化。**  
   service 使用 libphonenumber：

   ```java
   PhoneNumber parsed = phoneNumberUtil.parse(rawPhone, null);
   if (!phoneNumberUtil.isValidNumber(parsed)) { ... }
   return phoneNumberUtil.format(parsed, PhoneNumberFormat.E164);
   ```

   `region=null` 意味着输入应包含国际区号。结果作为查询、锁和数据库唯一性的统一值。

3. **获取细粒度注册锁。**  
   key 为 `putao:user:lock:register:phone:{phone}:{appName}`，等待 3 秒、固定租期 30 秒。拿不到锁或 Redis 异常都会终止流程，没有转为“只靠唯一约束”的降级路径。

4. **锁内重查绑定。**  
   SQL：

   ```sql
   SELECT * FROM user_login_phone
   WHERE phone_e164 = ? AND app_name = ? AND deleted = 0
   ```

   实际 `app_name` 是 Proto 枚举名，如 `APP_VIBE`，不是常量类定义的 `vibe`。

5. **命中已有绑定。**  
   manager 尝试更新 `last_open_at`，再读取 `user_info.pending`，返回 `newlyCreated=false`。`touchLastOpenAt` 的 SQL 是 UPDATE，但 Mapper 注解写成 `@Select`，存在运行时失败风险；若绑定仍活动而 `user_info` 已逻辑删除，随后直接访问 `.getPending()` 还会触发空指针。

6. **未命中时创建 placeholder。**  
   `SnowflakeIdGenerator.nextId()` 生成 userId，然后插入：

   ```java
   nickname = "User_" + userId;
   gender = 0;
   regulationStatus = 0;
   pending = 1;
   userType = 1;
   lastOpenAt = Instant.now();
   ```

7. **插入手机号绑定。**  
   写入 `user_id`、E.164 手机号、App 字符串、`verified_at=now`。数据库 `UNIQUE(phone_e164, app_name)` 是最终防线。

8. **处理唯一冲突。**  
   捕获 `DuplicateKeyException` 后重查绑定。若找到并发赢家，就刷新赢家的打开时间并返回赢家 userId；否则继续抛异常。

9. **释放锁并返回。**  
   finally 中仅当当前线程持锁时 unlock。新建返回 `pending=true,newlyCreated=true`；命中返回数据库中的 pending。

#### 事务、幂等和失败

`createPlaceholder()` 的 `@Transactional` 因 self-invocation 不生效，外层也无事务，所以 placeholder 与手机号绑定不是原子写。绑定失败或并发冲突可能留下无绑定的 pending 用户。重复请求通常能返回同一 userId，但不是严格意义上的单事务幂等。

### F002 第三方账号解析或创建

#### 完整调用链

```text
UserGrpcService.resolveOrCreateByThirdParty
→ UserIdentityGrpcImpl.resolveOrCreateByThirdParty
→ UserIdentityServiceImpl.resolveOrCreateByThirdParty
→ UserThirdPartyManager.findActive / insert
→ UserThirdPartyRegistrationMapper
→ user_info + user_third_party_registration
```

#### 分步执行

1. **入口检查第三方 ID。**  
   ID 必须非空；平台数值和 App 名交给 service 继续校验。Google 邮箱原样透传，没有格式校验。

2. **校验平台和 App。**  
   平台数值必须大于 0，因此 `TP_UNKNOWN` 被拒绝；App 只要求字符串非空，所以 `APP_UNSPECIFIED` 仍会被接受。

3. **获取第三方凭证锁。**  
   key 为 `putao:user:lock:register:tp:{platform}:{thirdPartyId}:{app}`，参数同样是 wait 3 秒、lease 30 秒。

4. **锁内查询活动绑定。**  
   条件包含 platform、thirdPartyUserId、appName 和 `deleted=0`。

5. **命中分支。**  
   刷新 `last_open_at`，读取 pending，返回原 userId。

6. **新建分支。**  
   创建默认 BH placeholder，再插入第三方绑定和可选邮箱。

7. **并发冲突分支。**  
   数据库通过部分 EXCLUDE 约束保证同一活动三元组唯一；冲突后重查赢家并返回。

8. **释放锁并返回。**  
   返回状态与手机号流程一致。

#### 数据库和一致性

```sql
EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
WHERE (deleted = 0)
```

软删除后可以重绑。placeholder 与绑定仍不在同一事务，具有相同的孤儿用户风险。

### F003 设备解析或创建

#### 完整调用链

```text
UserGrpcService.resolveOrCreateByDevice
→ UserIdentityGrpcImpl.resolveOrCreateByDevice
→ UserIdentityServiceImpl.resolveOrCreateByDevice
→ UserDeviceManager.findActive / insert
→ UserDeviceRegistrationMapper
→ user_info + user_device_registration
```

#### 分步执行

1. **入口检查 deviceId。**  
   非空后转换设备平台数值和 App 枚举名。

2. **service 校验。**  
   平台必须大于 0；deviceId 和 App 名必须非空。没有长度、签名、设备证明或频率校验。

3. **获取设备注册锁。**  
   key 为 `putao:user:lock:register:device:{deviceId}:{platform}:{app}`。

4. **查询活动设备绑定。**  
   条件为 deviceId、platform、appName、`deleted=0`。

5. **命中分支。**  
   刷新最近打开时间。这里对 `user_info` 做了 null 防护，找不到主资料时把 pending 计算为 false；与手机号/第三方分支的空指针行为不一致。

6. **新建分支。**  
   创建 BH placeholder，插入设备绑定。

7. **冲突重查。**  
   部分 EXCLUDE 约束兜底；重查成功则返回赢家。

8. **返回。**  
   新用户进入 onboarding；现有用户按 pending 分流。

#### 风险

没有账号升级/合并、设备可信校验或刷号限制。设备标识变化会创建新 placeholder，而不是找回旧用户。

## 四、监管判断

### F004 检查封禁状态

#### 完整调用链

```text
UserGrpcService.checkBan
→ UserIdentityGrpcImpl.checkBan
→ UserBanServiceImpl.checkBan
→ UserBanManager.queryBanReason
   → Redis GET putao:user:ban:status:{userId}
→ UserBanManager.isOperationalBanned
   → Redis SISMEMBER putao:user:ban:thirdparty-set
→ UserInfoManager.findByUserId
   → SELECT user_info
→ UserBanManager.reasonFromRegulationStatus
→ UserBanManager.cacheBanReason
   → Redis SET EX 5min
→ BanStatusVO → BanResult
```

#### 分步执行

1. **入口校验。**  
   userId 必须大于 0。

2. **读取短缓存。**  
   缓存值为 NORMAL、BANNED、SUSPENDED 或 OPERATIONAL。命中立即返回，不再检查运营集合或 DB。

3. **检查运营集合。**  
   `SISMEMBER` 命中则缓存 OPERATIONAL 并返回受限。只有这一项 Redis 读取捕获异常，失败时按未命中继续。

4. **读取数据库状态。**  
   `regulation_status=2` 映射 banned，5 映射 suspended，其他值和不存在用户都映射 normal。

5. **回填短缓存。**  
   正常结果也缓存 5 分钟。写缓存异常未捕获，会使本次检查失败。

6. **组装业务 VO。**  
   `bannedAtMs` 恒为 0；message 使用固定英文。

7. **转换 Proto reason。**  
   这里存在实现缺陷：service 传递 `BAN_REASON_USER_BANNED`，转换器却匹配 `USER_BANNED`，因此 Proto reason 会回落为 NONE，而 banned 仍可能为 true。

#### 一致性和降级

- 缓存优先导致运营封禁最多延迟一个 TTL 生效；
- 仓库没有运营集合写入和短缓存失效闭环；
- Redis 整体不可用时并非稳定回源 DB；
- 不存在用户按正常处理；
- `UserIdentityServiceImpl` 还保留一份几乎相同的 `checkBan`，但公开入口实际注入的是独立 `UserBanService`，形成重复逻辑。

## 五、资料读取生命周期

### F005 完整单人资料读取

#### 完整调用链

```text
UserGrpcService.getProfile
→ UserProfileGrpcImpl.getProfile
→ UserProfileServiceImpl.getProfile
→ UserProfileCacheManager.getProfileJson
→ miss: UserInfoManager.findByUserId → UserInfoMapper.selectByUserId
→ UserProfileConverter.toVO
→ UserProfileCacheManager.cacheProfileJson
→ loadInterests
   → Redis GET interest key
   → miss: UserInterestManager.listByUserId
   → UserInterestMapper.listByUserId
   → Redis SET interest key
→ UserProfileGrpcImpl.toProto
```

#### 分步执行

1. **入口校验。**  
   userId 必须大于 0；读取不校验 caller，也没有“只能看自己”的限制。

2. **读取主资料缓存。**  
   key 为 `putao:user:profile:{userId}`。JSON 反序列化失败会返回 null 并回源 DB，但坏缓存不会主动删除。

3. **主资料缓存 miss。**  
   SQL 按 `user_id` 且 `deleted=0` 查询。不存在时抛 `UserNotFoundException`。

4. **Entity 转 VO。**  
   MapStruct 映射主资料，`pending` 从 0/1 转布尔；`avatar`、`interests` 和 `lastOpenAtMs` 被显式忽略。`lastOpenAt` Instant 本身会按同名字段映射。

5. **回填主资料缓存。**  
   在兴趣挂载前序列化 VO，TTL 24 小时。写缓存失败只记 warn，不阻止返回。

6. **加载兴趣。**  
   先读 `putao:user:interest:{userId}`；miss 时执行：

   ```sql
   SELECT * FROM user_interest
   WHERE user_id = ?
   ORDER BY sort_order ASC, id ASC
   ```

   再回填 7 天。兴趣缓存读取异常没有捕获，仍可能使请求失败。

7. **处理头像。**  
   service 明确执行 `vo.setAvatar(null)`；V1 没有头像列，也没有大字段缓存的业务读取。

8. **组装 Proto。**  
   null 字符串转空字符串、null 数值转 0、pending 转 bool。由于 `lastOpenAtMs` 从未填充，返回值为 0。

#### 并发和一致性

没有负缓存、互斥重建或逻辑过期；热点 miss 可能并发回源。主资料与兴趣分别读取，返回不是同一数据库快照。

### F006 完整批量资料读取

#### 完整调用链

```text
UserGrpcService.batchGetProfile
→ UserProfileGrpcImpl.batchGetProfile
→ UserProfileServiceImpl.batchGetProfile
→ 对每个唯一 ID: Redis GET profile
→ misses: UserInfoManager.mapByUserIds
   → UserInfoMapper.selectByUserIds
→ 对每个命中 DB 的用户: Redis SET profile
→ includeInterests=true:
   UserInterestManager.mapByUserIds
   → UserInterestMapper.listByUserIds
→ 按输入首次出现顺序组装
```

#### 分步执行

1. **入口数量校验。**  
   原始列表不能为空且不超过 200。即使 201 个输入全部相同，也会先在 gRPC 层被拒绝。

2. **service 去重。**  
   使用 stream `distinct()` 保留首次出现顺序，再次确认唯一数量不超过 200。

3. **逐个读取主资料缓存。**  
   每个唯一 ID 单独 GET，不是 MGET/pipeline。命中 JSON 转成 VO，失败则记为 miss。

4. **一次批量查 DB。**  
   miss 集合执行 `WHERE user_id IN (...) AND deleted=0`。结果转 Map，因此 DB 返回顺序不影响后续输出。

5. **逐个回填缓存。**  
   每个 DB 用户单独 SET 24h。单个序列化或写缓存失败只 warn，但该实现把 `cachedMap.put` 放在同一个 try 中：若 SET 抛异常，对应 VO 也不会放入结果 Map，可能导致本次响应丢失本来已从 DB 查到的用户。

6. **可选批量兴趣查询。**  
   `includeInterests=true` 时，对全部唯一 ID 一次查询兴趣表；不读也不写兴趣 Redis 缓存。

7. **按输入顺序组装。**  
   重复 ID 只返回一次，不存在/已删除/因缓存回填异常未放入 Map 的用户被跳过。

8. **返回。**  
   头像仍为空。调用方需要用 userId 建 Map，不能假设响应长度等于请求长度。

#### 事务和一致性

纯读取，无数据库事务快照；缓存命中和 DB miss 可能观察到不同时间点的数据。

### F007 兼容单人资料读取

#### 完整调用链

```text
UserGrpcService.getUserProfile
→ UserProfileGrpcImpl.getUserProfile
→ UserProfileServiceImpl.getProfile
→ 同 F005 的缓存/DB/兴趣链
→ toLegacyProto
```

#### 分步执行

1. 校验 userId；
2. 完整执行 F005，即使兼容返回不需要兴趣，也仍加载兴趣；
3. 只选择 userId、nickname、avatar original key、bio、age、gender、createdAt；
4. 头像为空，gender 由 1/2 转成 `"MALE"`/`"FEMALE"`，其他值为空串；
5. 返回旧模型。

该接口被 mobile-gateway 使用；post-service 的部分旧链路使用批量兼容版。

### F009 兼容批量资料读取

#### 完整调用链

```text
UserGrpcService.batchGetUserProfiles
→ UserProfileGrpcImpl.batchGetUserProfiles
→ UserProfileServiceImpl.batchGetProfile(ids, false)
→ toLegacyProto
```

#### 分步执行

1. 原始输入非空且不超过 200；
2. 复用 F006 主资料读取，但强制不查兴趣；
3. 重复 ID 去重，缺失用户跳过；
4. 每个 VO 转成旧版简化资料；
5. 返回列表。

## 六、资料写入生命周期

### F010 编辑完整资料

#### 完整调用链

```text
UserGrpcService.updateProfile
→ UserProfileGrpcImpl.updateProfile
→ UserGrpcService.requireCallerUserId
→ UpdateProfileDTO
→ UserProfileServiceImpl.updateProfile @Transactional
→ UserInfoManager.findByUserId
→ 构造非 null patch + LambdaUpdateWrapper(id = physicalId)
→ UserInfoManager.updateSelective
→ UserProfileCacheManager.evictAll
→ UserProfileServiceImpl.getProfile
→ gRPC 层再次 profileService.getProfile
→ toProto
```

#### 分步执行

1. **取得调用者身份。**  
   从 gRPC Context 取 `x-user-id`。缺失时抛 BizException。若请求体 userId 大于 0 且不同于 caller，拒绝越权。

2. **归一化字段。**  
   空字符串转 null；age/height 为 0 也转 null。因此当前协议不能表达“清空字符串”或“显式设置为 0”。

3. **开启数据库事务并读取现有用户。**  
   通过业务 userId 找到实体和物理主键；不存在则 NOT_FOUND。

4. **逐字段校验并构造 patch。**  
   只有非 null 字段参与更新：

   ```java
   age: 0..150
   nickname: length <= 64, 入库前 trim
   preferredLocation / occupation / education: length <= 128
   bio: length <= 500
   height: 0..300
   ```

5. **无变更分支。**  
   若没有字段变化，直接调用 `getProfile` 返回，不写 DB。

6. **执行动态 UPDATE。**  
   wrapper 使用物理主键 `id`，MyBatis-Plus 只 SET patch 中的非 null 字段；DB 触发器和 MetaObjectHandler共同维护更新时间。

7. **事务内删除三类缓存。**  
   删除 profile、profileBig、interest。即使本次只改主资料，也会清兴趣缓存。

8. **事务内重读并回填。**  
   service 调用 `getProfile`，会看到本事务的新值，并在提交前写主资料缓存。

9. **gRPC 层重复读取。**  
   service 已返回 VO，但入口丢弃它，再调用一次 `getProfile`，造成一次额外缓存和兴趣读取。

10. **方法返回后提交事务。**  
    Spring 代理在 service 方法返回后提交 DB。缓存删除和重建均早于提交，存在旧值回填和未提交值提前缓存的窗口。

#### 失败和可见结果

- 校验失败整体不写；
- Redis 删除失败会抛异常并触发 DB 回滚；
- 缓存操作本身不会随 DB 回滚；
- 无乐观锁，两个并发编辑最后提交者覆盖重叠字段；
- 只返回最终完整资料，不返回字段级变更明细。

### F008 兼容资料编辑

#### 完整调用链

```text
UserGrpcService.updateUserProfile
→ UserProfileGrpcImpl.updateUserProfile
→ 与 F010 相同的 UpdateProfileDTO / service
→ toLegacyProto
```

#### 分步执行

1. 从 metadata 取得 caller；
2. 校验不能修改其他用户；
3. 旧请求仍能承载七个可编辑字段，转换规则与 F010 相同；
4. 执行同一事务、动态 UPDATE 和缓存失效链；
5. service 内已重读一次，gRPC 层再次重读；
6. 返回简化资料。

当前 mobile-gateway 的 `UserClient` 创建 stub 后没有附加 `x-user-id` metadata，所以仓库现状下该调用会在 user-service 入口缺少 caller。gateway 还为每次调用新建 channel 且未关闭，这是调用方问题，但会直接影响本功能可用性。

### F011 完成 onboarding

#### 完整调用链

```text
UserGrpcService.upsertOnboarding
→ UserProfileGrpcImpl.upsertOnboarding
→ requireCallerUserId
→ OnboardingDTO
→ UserProfileServiceImpl.upsertOnboarding @Transactional
→ UserInfoManager.findByUserId
→ UserInfoManager.updateSelective
→ 非空兴趣: self-call replaceInterests
   → UserInterestManager.deleteByUserId
   → UserInterestManager.insertOne × N
→ cacheManager.evictAll
→ getProfile
→ OnboardingResponse
```

#### 分步执行

1. **验证调用者和目标。**  
   必须有 caller；请求目标若与 caller 不同则拒绝。

2. **入口必填校验。**  
   gender 不能是 UNKNOWN；birthday 不能为空。生日在 try 内用 `LocalDate.parse` 解析，格式错误最终被包装成“internal error”再映射为 INVALID_ARGUMENT。

3. **构建 DTO。**  
   空字符串转 null；兴趣逐项转为 `InterestDTO`。

4. **事务内读取用户。**  
   不存在则失败；代码没有检查 `existing.pending`，因此不是一次性命令。

5. **service 再校验。**  
   nickname 必填且最长 64；gender 允许 0～2，但入口已经拒绝 0；birthday 必填。没有校验未来生日、最低年龄，也未完整校验 location/profession/education/height 的长度和范围。

6. **覆盖主资料。**  
   nickname、gender、birthday 总是写入，`pending=0`；其他非 null 字段按需写入。

7. **可选替换兴趣。**  
   只有兴趣列表非空才执行。内部 `replaceInterests` 虽然是 self-call，但外层事务已经有效开启，因此删除和插入仍参与同一个事务。图片最多 9、文字最多 50，逐行插入并设置 sortOrder。

8. **空兴趣分支。**  
   空列表不会删除历史兴趣，这与独立替换接口的“空列表=清空”不同。

9. **删除缓存并事务内重读。**  
   与 F010 一样，缓存操作发生在提交前。

10. **提交与返回。**  
    主资料和兴趣 DB 写入一起提交；返回完整资料。重复调用可覆盖性别和生日。

## 七、兴趣生命周期

### F012 全量替换兴趣

#### 完整调用链

```text
UserGrpcService.replaceUserInterests
→ UserProfileGrpcImpl.replaceUserInterests
→ requireCallerUserId
→ UserInterestServiceImpl.replaceUserInterests @Transactional
→ UserInterestManager.deleteByUserId
→ UserInterestMapper.deleteByUserId
→ UserInterestManager.insertOne × N
→ UserInterestMapper.insert
→ UserProfileCacheManager.evictAll
→ ReplaceUserInterestsResponse
```

#### 分步执行

1. **取得 caller 并做越权校验。**

2. **入口计数。**  
   带 `picKey` 的条目计为图片，最多 9；总条目最多 50。

3. **service 再校验。**  
   userId 必须非空。service 口径是图片最多 9、文字最多 50。没有先验证用户存在，依赖兴趣表外键在插入时发现无效用户。

4. **空列表分支。**  
   直接 DELETE 当前用户全部兴趣，删缓存，返回 0。

5. **删除旧兴趣。**  
   执行物理删除：

   ```sql
   DELETE FROM user_interest WHERE user_id = ?
   ```

6. **按顺序逐行插入。**  
   `sort_order` 从 0 递增。不是 JDBC batch；最多 50 次入口插入。

7. **唯一和字段约束。**  
   `(user_id, tab_key, tag_key)` 重复或 tab/tag 为 null 会使事务回滚；空字符串则能入库，因为入口没有内容校验。

8. **删除缓存。**  
   profile、profileBig、interest 全删，发生在 DB 提交前。

9. **提交并返回计数。**  
   返回 `dtos.size()`；Proto 列表不会含 null，所以等于插入尝试数。并发请求使用 last-commit-wins，没有版本冲突提示。

## 八、头像生命周期

### F013 生成头像上传地址

#### 完整调用链

```text
UserGrpcService.presignAvatarUpload
→ UserProfileGrpcImpl.presignAvatarUpload
→ requireCallerUserId
→ UserAvatarServiceImpl.presignUpload
→ UserInfoManager.findByUserId
→ UUID objectKey
→ presignUrlPlaceholder
→ PresignAvatarUploadResponse
```

#### 分步执行

1. caller 必须存在，且不能为其他用户申请；
2. 扩展名转小写，只允许 jpg/jpeg/png/webp；
3. `sizeBytes > 10MB` 时拒绝；0 或负数不会被拒绝；
4. 查询 `user_info` 确认用户存在；
5. 生成 `avatar/{userId}/{uuid}.{ext}`；
6. 直接拼接固定 MinIO URL，签名值为 `PLACEHOLDER`；
7. 计算 `now + 5min` 作为展示给客户端的过期时间；
8. 返回 URL、key 和过期时间。

没有对象存储 client、真实签名、bucket 配置读取或 Content-Length 约束，所以当前 URL 不构成可用 presigned PUT 凭证。

### F014 确认头像上传

#### 完整调用链

```text
UserGrpcService.confirmAvatarUpload
→ UserProfileGrpcImpl.confirmAvatarUpload
→ requireCallerUserId
→ UserAvatarServiceImpl.confirmUpload
→ UserInfoManager.findByUserId
→ objectKey prefix validation
→ UserProfileCacheManager.evictAll
→ UserProfileService.getProfile
→ ConfirmAvatarUploadResponse
```

#### 分步执行

1. caller 和 objectKey 必须存在；
2. 请求目标不能是其他用户；
3. 查询用户存在性；
4. 要求 objectKey 以 `avatar/{callerId}/` 开头，阻止直接确认他人目录；
5. 不调用 headObject，不检查对象是否真正上传；
6. 不检查扩展名、大小、媒体内容和本次 presign 的关联；
7. V1 没有头像列，因此不写数据库；
8. 只删除缓存，然后重读资料；
9. `getProfile` 固定 avatar 为空，所以确认前后返回无变化。

该流程只实现了前缀授权检查，不具备确认幂等记录、孤儿对象清理或头像持久化闭环。

## 九、契约存在但服务未实现

### F015 查询用户类型

#### 声明调用链

```text
match-service / im-service
→ UserServiceGrpc.UserServiceBlockingStub.getUserType
→ UserGrpcService 基类默认实现
→ UNIMPLEMENTED
```

#### 分步执行

1. Proto 已声明 `GetUserType`；
2. `user_info.user_type` 已有 1=BH、2=DH 字段；
3. `UserGrpcService` 没有 override；
4. 请求不会进入 manager 或 DB；
5. grpc-java 返回 UNIMPLEMENTED；
6. match-service 捕获后返回 `-1`；
7. im-service 捕获后按 BH（false）处理。

### F016 DH 候选召回

#### 声明调用链

```text
match-service CandidateRecaller
→ UserServiceClient.listDhCandidates
→ gRPC stub
→ UserGrpcService 基类默认实现
→ UNIMPLEMENTED
→ 调用方返回 emptyList
```

#### 分步执行

1. Proto 声明性别、年龄、颜值、种族、排除集合和 limit；
2. V1 创建了 `(user_type, gender, city_id, age, beauty_score)` 部分索引；
3. user-service 没有 mapper 查询、service 编排或 gRPC override；
4. 所有筛选条件都未执行；
5. match-service 把异常降级为空候选；
6. 空集合代表调用失败，不是数据库真实无候选。

### F017 附近 BH 召回

#### 声明调用链

```text
match-service CandidateRecaller
→ UserServiceClient.nearbyUsers
→ gRPC stub
→ UserGrpcService 基类默认实现
→ UNIMPLEMENTED
→ 调用方返回 emptyList
```

#### 分步执行

1. Proto 声明半径、年龄、颜值、种族、活跃天数和排除集合；
2. `user_info` 预留 lat/lng/cityId/lastOpenAt；
3. 当前没有 GEO 写入流程、空间索引或距离 SQL；
4. `UserGrpcService` 未覆写；
5. match-service 捕获异常并返回空集合；
6. 任何关于 100km/200km 召回效果的描述都属于设计意图，不是当前实现。

## 十、启动和维护流程

### M001 服务启动

#### 完整调用链

```text
UserApplication.main
→ SpringApplication.run
→ Nacos Config import（optional）
→ DataSource / Redis / Redisson / gRPC beans
→ Flyway migrate
→ RedisKeyPrefixConfig.init
→ gRPC server :19081
→ Actuator HTTP :18081
```

#### 分步执行

1. 读取本地 `application.yml`，profile 默认 dev；
2. 尝试导入 `dating-user-service-dev.yaml`；
3. 数据库连接应在外部配置中提供，并使用 UTC；
4. Flyway 从 `classpath:db/migration` 执行 V1，历史表为 `flyway_history_user`；
5. V1 创建 `btree_gist`、五张表、索引、外键、EXCLUDE 约束和 `updated_at` 触发器；
6. `RedisKeyPrefixConfig` 把 `app.cache.key-prefix` 写入静态 `RedisKey`，默认 `putao:user`；
7. Snowflake bean 读取 worker/datacenter，默认均为 1；
8. gRPC 监听 19081，HTTP 18081 只暴露 Actuator；
9. 服务注册到 Nacos。

#### 启动风险

- 本地配置包含 Nacos 地址和默认凭证，不符合“仓库只保留占位符”的设计稿目标；
- 多实例复用 Snowflake 编号可能生成重复 ID；
- application 没有对象存储配置，因为头像尚未真实接入；
- user-service 没有自动修复孤儿 placeholder、缓存预热或数据巡检任务。

### M002/M003 定时任务和消息

源码中没有 `@Scheduled`、消息 producer、consumer、topic、outbox 或重试任务。用户资料变更不会主动通知下游；下游只能依赖自己的 TTL 或再次调用 user-service 观察变化。

## 十一、存储清单

### 11.1 PostgreSQL

| 表 | 主用途 | 关键约束 |
|---|---|---|
| `user_info` | 主资料、监管、类型、位置、pending | `user_id` 唯一；逻辑删除；更新时间触发器 |
| `user_login_phone` | 手机号绑定 | `(phone_e164, app_name)` 物理唯一 |
| `user_third_party_registration` | 第三方绑定 | 活动记录部分 EXCLUDE 唯一 |
| `user_device_registration` | 设备绑定 | 活动记录部分 EXCLUDE 唯一 |
| `user_interest` | 兴趣标签 | `(user_id, tab_key, tag_key)` 唯一；按 sort_order 读取 |

### 11.2 Redis

| Key | 数据类型 | TTL | 使用方 |
|---|---|---:|---|
| `putao:user:profile:{userId}` | String JSON | 24h | 单人/批量资料 |
| `putao:user:profile:big:{userId}` | String JSON | 24h | 仅预留方法 |
| `putao:user:interest:{userId}` | String JSON | 7d | 单人资料 |
| `putao:user:ban:status:{userId}` | String | 5min | 封禁短缓存 |
| `putao:user:ban:thirdparty-set` | Set | 无 | 运营封禁读取 |
| `putao:user:lock:register:*` | Redisson lock | 30s lease | 三类身份解析 |

## 十二、异步流程汇总

当前没有服务内异步业务闭环：

- 无 MQ；
- 无 outbox；
- 无 scheduler；
- 无头像异步缩略图；
- 无缓存异步失效；
- 无注册孤儿清理；
- 无封禁状态广播。

唯一的“最终变化”来自 Redis TTL 自然过期，这不是可靠事件传播机制。

## 十三、实现状态与偏差

| 能力 | 状态 | 关键偏差/风险 |
|---|---|---|
| 三类身份解析 | 已实现 | placeholder 与绑定不原子；App 存枚举名；最近打开 SQL 注解可疑 |
| 封禁检查 | 部分实现 | Redis 故障不完整降级；reason 映射错误；不存在用户按正常 |
| 单人资料 | 已实现 | avatar 空、lastOpenAtMs 为 0、无负缓存 |
| 批量资料 | 已实现 | 逐个 GET/SET，不是 MGET；缺失静默跳过 |
| 资料编辑 | 已实现 | 不能清空字段；缓存失效早于事务提交 |
| onboarding | 已实现 | 可重复覆盖；空兴趣不清空；部分字段缺校验 |
| 兴趣替换 | 已实现 | 逐行插入；入口计数口径不统一；last-commit-wins |
| 头像 | 占位 | 无真实签名、对象验证、DB 字段或返回头像 |
| 用户类型 | 未实现 | 调用方降级为未知或 BH |
| DH/BH 召回 | 未实现 | 调用方得到空集合 |
| 定时任务/MQ | 不存在 | 用户变更无主动传播 |
| 自动化测试 | 不存在 | 关键事务、SQL 注解和故障分支未被测试保护 |

## 十四、功能到代码索引

| 功能 | 入口 | 核心 service | Manager/Mapper | 数据 |
|---|---|---|---|---|
| F001～F003 身份解析 | `grpc/UserIdentityGrpcImpl.java` | `service/impl/UserIdentityServiceImpl.java` | `UserInfoManager` + 三类绑定 Manager/Mapper | `user_info` + 绑定表 + Redisson |
| F004 封禁 | `grpc/UserIdentityGrpcImpl.java` | `service/impl/UserBanServiceImpl.java` | `UserBanManager`、`UserInfoManager` | Redis + `user_info` |
| F005～F010 资料读写 | `grpc/UserProfileGrpcImpl.java` | `service/impl/UserProfileServiceImpl.java` | `UserInfoManager`、`UserInterestManager`、`UserProfileCacheManager` | PG + Redis |
| F011 onboarding | `grpc/UserProfileGrpcImpl.java` | `UserProfileServiceImpl.upsertOnboarding` | 用户/兴趣 Manager | `user_info` + `user_interest` |
| F012 兴趣 | `grpc/UserProfileGrpcImpl.java` | `service/impl/UserInterestServiceImpl.java` | `UserInterestManager/Mapper` | `user_interest` + Redis |
| F013～F014 头像 | `grpc/UserProfileGrpcImpl.java` | `service/impl/UserAvatarServiceImpl.java` | `UserInfoManager`、缓存 Manager | 当前无对象存储/头像列 |
| F015～F017 未实现接口 | `proto/user/user.proto` | 无 | 无 | 仅 schema 预留字段 |
| M001 启动 | `UserApplication.java`、`config/*` | Flyway | V1 migration | PG/Redis/Nacos |
