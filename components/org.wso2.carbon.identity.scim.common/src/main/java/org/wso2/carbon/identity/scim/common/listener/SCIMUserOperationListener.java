/*
 * Copyright (c) 2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.scim.common.listener;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.common.model.ThreadLocalProvisioningServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.core.AbstractIdentityUserOperationEventListener;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.scim.common.group.SCIMGroupHandler;
import org.wso2.carbon.identity.scim.common.utils.IdentitySCIMException;
import org.wso2.carbon.identity.scim.common.utils.SCIMCommonConstants;
import org.wso2.carbon.identity.scim.common.utils.SCIMCommonUtils;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.charon.core.schema.SCIMConstants;
import org.wso2.charon.core.util.AttributeUtil;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * This is to perform SCIM related operation on User Operations.
 * For eg: when a user is created through UserAdmin API, we need to set some SCIM specific properties
 * as user attributes.
 */
public class SCIMUserOperationListener extends AbstractIdentityUserOperationEventListener {

    private static Log log = LogFactory.getLog(SCIMUserOperationListener.class);

    @Override
    public int getExecutionOrderId() {
        int orderId = getOrderId();
        if (orderId != IdentityCoreConstants.EVENT_LISTENER_ORDER_ID) {
            return orderId;
        }
        return 90;
    }

    @Override
    public boolean doPreAuthenticate(String s, Object o, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostAuthenticate(String userName, boolean authenticated,
                                      UserStoreManager userStoreManager)
            throws UserStoreException {

        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        String domainName = userStoreManager.getRealmConfiguration().getUserStoreProperty(
                UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        if(authenticated){
            if (StringUtils.isNotEmpty(UserCoreUtil.getDomainFromThreadLocal())) {
                if(!StringUtils.equals(UserCoreUtil.getDomainFromThreadLocal(), domainName)){
                    return true;
                }
            } else if (!StringUtils.equals(UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME, domainName)){
                return true;
            }
        } else {
            String usernameWithDomain = UserCoreUtil.addDomainToName(userName, domainName);
            boolean isUserExistInCurrentDomain = userStoreManager.isExistingUser(usernameWithDomain);
            if (!isUserExistInCurrentDomain) {
                if (log.isDebugEnabled()) {
                    log.debug("User, " + userName + " does not exist in " + domainName);
                }
                return true;
            }
        }

        try {
            String activeAttributeValue = userStoreManager.getUserClaimValue(userName, SCIMConstants.ACTIVE_URI, null);
            boolean isUserActive = true;
            if (activeAttributeValue != null) {
                isUserActive = Boolean.parseBoolean(activeAttributeValue);
                if (isUserActive) {
                    return true;
                } else {
                    log.error("Trying to login from an inactive account of user: " + userName);
                    return false;
                }
            }
            return true;
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            if (e.getMessage().contains("UserNotFound")){
                if (log.isDebugEnabled()){
                    log.debug("User " + userName + " not found in user store", e);
                }
                return false;
            }
            throw new UserStoreException(e);
        }

    }

    @Override
    public boolean doPreAddUser(String userName, Object credential, String[] roleList,
                                Map<String, String> claims, String profile,
                                UserStoreManager userStoreManager) throws UserStoreException {

        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }
        this.getSCIMAttributes(userName, claims);
        return true;
    }

    @Override
    public boolean doPostAddUser(String userName, Object credential, String[] roleList,
                                 Map<String, String> claims, String profile,
                                 UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPreUpdateCredential(String s, Object o, Object o1,
                                         UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostUpdateCredential(String userName, Object credential, UserStoreManager userStoreManager)
            throws UserStoreException {
        return doPostUpdateCredentialByAdmin(userName, credential, userStoreManager);
    }

    @Override
    public boolean doPreUpdateCredentialByAdmin(String s, Object o,
                                                UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostUpdateCredentialByAdmin(String userName, Object credential,
                                                 UserStoreManager userStoreManager)
            throws UserStoreException {

        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        //update last-modified-date
        try {
            Date date = new Date();
            String lastModifiedDate = AttributeUtil.formatDateTime(date);
            userStoreManager.setUserClaimValue(
                    userName, SCIMConstants.META_LAST_MODIFIED_URI, lastModifiedDate, null);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            if (e.getMessage().contains("UserNotFound")) {
                if (log.isDebugEnabled()) {
                    log.debug("User " + userName + " doesn't exist");
                }
            } else {
                throw new UserStoreException("Error updating SCIM metadata in doPostUpdateCredentialByAdmin " +
                        "listener", e);
            }
        }
        return true;
    }

    @Override
    public boolean doPreDeleteUser(String userName, UserStoreManager userStoreManager)
            throws UserStoreException {

            return true;

    }

    @Override
    public boolean doPostDeleteUser(String s, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPreSetUserClaimValue(String s, String s1, String s2, String s3,
                                          UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostSetUserClaimValue(String s, UserStoreManager userStoreManager)
            throws UserStoreException {
        //TODO: need to set last modified time.
        return true;
    }

    @Override
    public boolean doPreSetUserClaimValues(String userName, Map<String, String> claims,
                                           String profileName, UserStoreManager userStoreManager)
            throws UserStoreException {
        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        Date date = new Date();
        String lastModifiedDate = AttributeUtil.formatDateTime(date);
        claims.put(SCIMConstants.META_LAST_MODIFIED_URI, lastModifiedDate);

        return true;
    }

    @Override
    public boolean doPostSetUserClaimValues(String userName, Map<String, String> claims,
                                            String profileName, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPreDeleteUserClaimValues(String s, String[] strings, String s1,
                                              UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostDeleteUserClaimValues(String s, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPreDeleteUserClaimValue(String s, String s1, String s2,
                                             UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostDeleteUserClaimValue(String s, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPreAddRole(String s, String[] strings,
                                org.wso2.carbon.user.api.Permission[] permissions,
                                UserStoreManager userStoreManager) throws UserStoreException {

        return true;
    }

    @Override
    public boolean doPostAddRole(String roleName, String[] userList,
                                 org.wso2.carbon.user.api.Permission[] permissions,
                                 UserStoreManager userStoreManager) throws UserStoreException {

        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        try {

            SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(userStoreManager.getTenantId());

            String domainName = UserCoreUtil.getDomainName(userStoreManager.getRealmConfiguration());
            if (domainName == null) {
                domainName = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
            }
            String roleNameWithDomain = UserCoreUtil.addDomainToName(roleName, domainName);
            // UserCore Util functionality does not append primary
            roleNameWithDomain = SCIMCommonUtils.getGroupNameWithDomain(roleNameWithDomain);

            //query role name from identity table
            try {
                if (!scimGroupHandler.isGroupExisting(roleNameWithDomain)) {
                    //if no attributes - i.e: group added via mgt console, not via SCIM endpoint
                    //add META
                    scimGroupHandler.addMandatoryAttributes(roleNameWithDomain);
                }
            } catch (IdentitySCIMException e) {
                throw new UserStoreException("Error retrieving group information from SCIM Tables.", e);
            }

            return true;

        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException(e);
        }

    }

    @Override
    public boolean doPreDeleteRole(String roleName, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostDeleteRole(String roleName, UserStoreManager userStoreManager)
            throws UserStoreException {
        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        try {
            SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(userStoreManager.getTenantId());

            String domainName = UserCoreUtil.getDomainName(userStoreManager.getRealmConfiguration());
            if (domainName == null) {
                domainName = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
            }
            String roleNameWithDomain = IdentityUtil.addDomainToName(roleName, domainName);

            try {
                //delete group attributes - no need to check existence here, since it is checked in below method.
                //remove SCIM attributes for the group added via mgt console, not via SCIM endpoint
                scimGroupHandler.deleteGroupAttributes(roleNameWithDomain);
            } catch (IdentitySCIMException e) {
                throw new UserStoreException("Error retrieving group information from SCIM Tables.", e);
            }

            return true;
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException(e);
        }
    }

    @Override
    public boolean doPreUpdateRoleName(String s, String s1, UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostUpdateRoleName(String roleName, String newRoleName,
                                        UserStoreManager userStoreManager)
            throws UserStoreException {

        try {
            if (!isEnable() || !userStoreManager.isSCIMEnabled()) {
                return true;
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException("Error while reading isScimEnabled from userstore manager", e);
        }

        try {
            //TODO:set last update date
            SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(userStoreManager.getTenantId());

            String domainName = UserCoreUtil.getDomainName(userStoreManager.getRealmConfiguration());
            if (domainName == null) {
                domainName = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
            }
            String roleNameWithDomain = UserCoreUtil.addDomainToName(roleName, domainName);
            String newRoleNameWithDomain = UserCoreUtil.addDomainToName(newRoleName, domainName);
            try {
                scimGroupHandler.updateRoleName(roleNameWithDomain, newRoleNameWithDomain);

            } catch (IdentitySCIMException e) {
                throw new UserStoreException("Error updating group information in SCIM Tables.", e);
            }
            return true;

        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new UserStoreException(e);
        }


    }

    @Override
    public boolean doPreUpdateUserListOfRole(String s, String[] strings, String[] strings1,
                                             UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostUpdateUserListOfRole(String roleName, String[] deletedUsers,
                                              String[] newUsers, UserStoreManager userStoreManager)
            throws UserStoreException {

            return true;

    }

    @Override
    public boolean doPreUpdateRoleListOfUser(String s, String[] strings, String[] strings1,
                                             UserStoreManager userStoreManager)
            throws UserStoreException {
        return true;
    }

    @Override
    public boolean doPostUpdateRoleListOfUser(String s, String[] strings, String[] strings1,
                                              UserStoreManager userStoreManager)
            throws UserStoreException {
        //TODO:
        return true;
    }

    public Map<String, String> getSCIMAttributes(String userName, Map<String, String> claimsMap) throws UserStoreException {
        Map<String, String> attributes = null;
        if (claimsMap != null) {
            attributes = claimsMap;
        } else {
            attributes = new HashMap<>();
        }

        Map<String, String> scimToLocalMappings = SCIMCommonUtils.getSCIMtoLocalMappings();
        String userIdLocalClaimUri = scimToLocalMappings.get(SCIMConstants.ID_URI);
        String createdLocalClaimUri = scimToLocalMappings.get(SCIMConstants.META_CREATED_URI);
        String modifiedLocalClaimUri = scimToLocalMappings.get(SCIMConstants.META_LAST_MODIFIED_URI);
        String usernameLocalClaimUri = scimToLocalMappings.get(SCIMConstants.USER_NAME_URI);

        Pattern pattern = Pattern.compile("urn:.*scim:schemas:core:.\\.0:id");
        boolean containsScimIdClaim = false;
        for (String claimUri : attributes.keySet()) {
            if (pattern.matcher(claimUri).matches()) {
                containsScimIdClaim = true;
                break;
            }
            if (StringUtils.equals(claimUri, userIdLocalClaimUri)) {
                containsScimIdClaim = true;
                break;
            }
        }
        if (!containsScimIdClaim) {
            String id = UUID.randomUUID().toString();
            attributes.put(userIdLocalClaimUri, id);
        }

        Date date = new Date();
        String createdDate = AttributeUtil.formatDateTime(date);

        if (!attributes.containsKey(SCIMConstants.META_CREATED_URI) || !attributes.containsKey(createdLocalClaimUri)) {
            attributes.put(createdLocalClaimUri, createdDate);
        }

        if (!attributes.containsKey(SCIMConstants.META_LAST_MODIFIED_URI) || !attributes.containsKey(modifiedLocalClaimUri)) {
            attributes.put(modifiedLocalClaimUri, createdDate);
        }

        attributes.put(usernameLocalClaimUri, userName);

        return attributes;
        //TODO: add other optional attributes like location etc.
    }

}
