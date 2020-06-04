package com.exadel.frs.service;

import static com.exadel.frs.enums.AppRole.ADMINISTRATOR;
import static com.exadel.frs.enums.AppRole.OWNER;
import static com.exadel.frs.enums.OrganizationRole.USER;
import static org.apache.commons.lang3.BooleanUtils.isNotTrue;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import com.exadel.frs.dto.ui.AppCreateDto;
import com.exadel.frs.dto.ui.AppUpdateDto;
import com.exadel.frs.dto.ui.UserInviteDto;
import com.exadel.frs.dto.ui.UserRoleUpdateDto;
import com.exadel.frs.entity.App;
import com.exadel.frs.entity.ModelShareRequest;
import com.exadel.frs.entity.ModelShareRequestId;
import com.exadel.frs.entity.Organization;
import com.exadel.frs.entity.UserAppRole;
import com.exadel.frs.enums.AppRole;
import com.exadel.frs.enums.OrganizationRole;
import com.exadel.frs.exception.AppDoesNotBelongToOrgException;
import com.exadel.frs.exception.AppNotFoundException;
import com.exadel.frs.exception.InsufficientPrivilegesException;
import com.exadel.frs.exception.NameIsNotUniqueException;
import com.exadel.frs.exception.SelfRoleChangeException;
import com.exadel.frs.exception.UserAlreadyHasAccessToAppException;
import com.exadel.frs.helpers.SecurityUtils;
import com.exadel.frs.repository.AppRepository;
import com.exadel.frs.repository.ModelShareRequestRepository;
import com.exadel.frs.system.rest.CoreFacesClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppService {

    private final AppRepository appRepository;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final ModelShareRequestRepository modelShareRequestRepository;
    private final CoreFacesClient coreFacesClient;

    public App getApp(final String appGuid) {
        return appRepository.findByGuid(appGuid)
                            .orElseThrow(() -> new AppNotFoundException(appGuid));
    }

    private OrganizationRole getUserOrganizationRole(final Organization organization, final Long userId) {
        return organization.getUserOrganizationRoleOrThrow(userId).getRole();
    }

    private void verifyUserHasReadPrivileges(final Long userId, final App app) {
        if (USER == getUserOrganizationRole(app.getOrganization(), userId)) {
            app.getUserAppRole(userId)
               .orElseThrow(InsufficientPrivilegesException::new);
        }
    }

    private void verifyUserHasWritePrivileges(final Long userId, final App app) {
        if (USER == getUserOrganizationRole(app.getOrganization(), userId)) {
            val role = app.getUserAppRole(userId)
                          .orElseThrow(InsufficientPrivilegesException::new)
                          .getRole();

            if (AppRole.USER == role) {
                throw new InsufficientPrivilegesException();
            }
        }
    }

    private void verifyNameIsUnique(final String name, final Long orgId) {
        if (appRepository.existsByNameAndOrganizationId(name, orgId)) {
            throw new NameIsNotUniqueException(name);
        }
    }

    private void verifyOrganizationHasTheApp(final String orgGuid, final App app) {
        if (!app.getOrganization().getGuid().equals(orgGuid)) {
            throw new AppDoesNotBelongToOrgException(app.getGuid(), orgGuid);
        }
    }

    public App getApp(final String orgGuid, final String appGuid, final Long userId) {
        val app = getApp(appGuid);

        verifyUserHasReadPrivileges(userId, app);
        verifyOrganizationHasTheApp(orgGuid, app);

        return app;
    }

    public List<App> getApps(final String orgGuid, final Long userId) {
        val organization = organizationService.getOrganization(orgGuid);

        if (USER == getUserOrganizationRole(organization, userId)) {
            return appRepository.findAllByOrganizationIdAndUserAppRoles_Id_UserId(organization.getId(), userId);
        }

        return appRepository.findAllByOrganizationId(organization.getId());
    }

    public AppRole[] getAppRolesToAssign(final String orgGuid, final String appGuid, final Long userId) {
        val app = getApp(orgGuid, appGuid, userId);

        val userAppRole = app.getUserAppRole(userId);
        if (userAppRole.isPresent() && OWNER == userAppRole.get().getRole()) {
            return AppRole.values();
        }

        val orgRole = app.getOrganization().getUserOrganizationRoleOrThrow(userId);
        if (USER == orgRole.getRole()) {
            return new AppRole[0];
        }

        return AppRole.values();
    }

    public List<UserAppRole> getAppUsers(final String searchText, final String orgGuid, final String appGuid, final Long userId) {
        val app = getApp(orgGuid, appGuid, userId);

        if (isNotEmpty(searchText)) {
            val result = new ArrayList<UserAppRole>();
            app.getUserAppRoles().forEach(userAppRole -> {
                val searchTextLowerCase = searchText.toLowerCase();
                if (userAppRole.getUser().getFirstName().toLowerCase().contains(searchTextLowerCase) ||
                        userAppRole.getUser().getLastName().toLowerCase().contains(searchTextLowerCase) ||
                        userAppRole.getUser().getEmail().toLowerCase().contains(searchTextLowerCase)) {
                    result.add(userAppRole);
                }
            });

            return result;
        }

        return app.getUserAppRoles();
    }

    public UserAppRole inviteUser(
            final UserInviteDto userInviteDto,
            final String orgGuid,
            final String appGuid,
            final Long userId
    ) {
        val app = getApp(orgGuid, appGuid, userId);

        verifyUserHasWritePrivileges(userId, app);

        val user = userService.getUser(userInviteDto.getUserEmail());
        val userOrgRole = app.getOrganization().getUserOrganizationRoleOrThrow(user.getId());
        val userAppRole = app.getUserAppRole(user.getId());
        if (USER != userOrgRole.getRole() || userAppRole.isPresent()) {
            throw new UserAlreadyHasAccessToAppException(userInviteDto.getUserEmail(), appGuid);
        }

        val appRole = AppRole.valueOf(userInviteDto.getRole());
        if (OWNER == appRole) {
            app.getOwner().ifPresent(previousOwner -> previousOwner.setRole(ADMINISTRATOR));
        }

        app.addUserAppRole(user, appRole);
        val savedApp = appRepository.save(app);

        return savedApp.getUserAppRole(user.getId()).orElseThrow();
    }

    public App createApp(final AppCreateDto appCreateDto, final String orgGuid, final Long userId) {
        val organization = organizationService.getOrganization(orgGuid);

        organizationService.verifyUserHasWritePrivileges(userId, organization);

        verifyNameIsUnique(appCreateDto.getName(), organization.getId());

        val app = App.builder()
                     .name(appCreateDto.getName())
                     .organization(organization)
                     .guid(UUID.randomUUID().toString())
                     .apiKey(UUID.randomUUID().toString())
                     .build();

        app.addUserAppRole(userService.getUser(userId), OWNER);

        return appRepository.save(app);
    }

    public App updateApp(final AppUpdateDto appUpdateDto, final String orgGuid, final String appGuid, final Long userId) {
        val app = getApp(orgGuid, appGuid, userId);

        verifyUserHasWritePrivileges(userId, app);

        val isSameName = app.getName().equals(appUpdateDto.getName());
        if (isNotTrue(isSameName)) {
            verifyNameIsUnique(appUpdateDto.getName(), app.getOrganization().getId());
            app.setName(appUpdateDto.getName());
        }

        return appRepository.save(app);
    }

    public UserAppRole updateUserAppRole(final UserRoleUpdateDto userRoleUpdateDto, final String orgGuid, final String guid, final Long adminId) {
        val app = getApp(orgGuid, guid, adminId);

        verifyUserHasWritePrivileges(adminId, app);

        val user = userService.getUserByGuid(userRoleUpdateDto.getUserId());
        if (user.getId().equals(adminId)) {
            throw new SelfRoleChangeException();
        }

        val userAppRole = app.getUserAppRole(user.getId()).orElseThrow();
        val newAppRole = AppRole.valueOf(userRoleUpdateDto.getRole());
        if (OWNER == newAppRole) {
            app.getOwner().ifPresent(previousOwner -> previousOwner.setRole(ADMINISTRATOR));
        }

        userAppRole.setRole(newAppRole);

        appRepository.save(app);

        return userAppRole;
    }

    public void deleteUserFromApp(final String userGuid, final String orgGuid, final String guid, final Long adminId) {
        val userId = userService.getUserByGuid(userGuid).getId();
        val app = getApp(orgGuid, guid, userId);

        verifyUserHasWritePrivileges(adminId, app);

        app.deleteUserAppRole(userGuid);

        appRepository.save(app);
    }

    public void regenerateApiKey(final String orgGuid, final String guid, final Long userId) {
        val app = getApp(orgGuid, guid, userId);

        verifyUserHasWritePrivileges(userId, app);

        app.setApiKey(UUID.randomUUID().toString());

        appRepository.save(app);
    }

    @Transactional
    public void deleteApp(final String orgGuid, final String guid, final Long userId) {
        val app = getApp(orgGuid, guid, userId);

        verifyUserHasWritePrivileges(userId, app);

        app.getModels().forEach(model ->
                coreFacesClient.deleteFaces(model.getApiKey())
        );

        appRepository.deleteById(app.getId());
    }

    public UUID generateUuidToRequestModelShare(final String orgGuid, final String appGuid) {
        val app = getApp(appGuid);

        verifyUserHasWritePrivileges(SecurityUtils.getPrincipalId(), app);
        verifyOrganizationHasTheApp(orgGuid, app);

        val requestId = UUID.randomUUID();
        val id = ModelShareRequestId.builder()
                                    .appId(app.getId())
                                    .requestId(requestId)
                                    .build();

        val shareRequest = ModelShareRequest.builder()
                                            .app(app)
                                            .id(id)
                                            .build();

        modelShareRequestRepository.save(shareRequest);

        return requestId;
    }
}