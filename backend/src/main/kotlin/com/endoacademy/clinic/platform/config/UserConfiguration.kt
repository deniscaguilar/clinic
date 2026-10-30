package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcMemberStore
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.application.DeactivateMembership
import com.endoacademy.clinic.platform.application.IdentityProvider
import com.endoacademy.clinic.platform.application.InviteUser
import com.endoacademy.clinic.platform.application.ListUsers
import com.endoacademy.clinic.platform.application.MemberStore
import com.endoacademy.clinic.platform.application.ResendInvitation
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateUserRoles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class UserConfiguration {

    @Bean
    fun memberStore(jdbc: JdbcTemplate): MemberStore = JdbcMemberStore(jdbc)

    @Bean
    fun listUsers(authorizer: Authorizer, store: MemberStore, transactions: Transactions) = ListUsers(authorizer, store, transactions)

    @Bean
    fun inviteUser(authorizer: Authorizer, store: MemberStore, identityProvider: IdentityProvider, auditTrail: AuditTrail, transactions: Transactions) =
        InviteUser(authorizer, store, identityProvider, auditTrail, transactions)

    @Bean
    fun updateUserRoles(authorizer: Authorizer, store: MemberStore, auditTrail: AuditTrail, transactions: Transactions) =
        UpdateUserRoles(authorizer, store, auditTrail, transactions)

    @Bean
    fun deactivateMembership(authorizer: Authorizer, store: MemberStore, auditTrail: AuditTrail, transactions: Transactions) =
        DeactivateMembership(authorizer, store, auditTrail, transactions)

    @Bean
    fun resendInvitation(authorizer: Authorizer, store: MemberStore, identityProvider: IdentityProvider, auditTrail: AuditTrail, transactions: Transactions) =
        ResendInvitation(authorizer, store, identityProvider, auditTrail, transactions)
}
