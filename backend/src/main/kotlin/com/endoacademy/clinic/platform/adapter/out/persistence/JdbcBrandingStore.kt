package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.application.BrandingStore
import com.endoacademy.clinic.platform.domain.Branding
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** `tenant_branding` na transação do chamador; a RLS limita à clínica ativa. */
class JdbcBrandingStore(private val jdbc: JdbcTemplate) : BrandingStore {

    override fun find(): Branding? = jdbc.query(
        "select display_name, primary_color, secondary_color, logo_key, favicon_key from platform.tenant_branding",
        { rs, _ ->
            Branding.of(rs.getString("display_name"), rs.getString("primary_color"), rs.getString("secondary_color"), rs.getString("logo_key"), rs.getString("favicon_key"))
        },
    ).firstOrNull()

    override fun save(clinicId: UUID, branding: Branding) {
        jdbc.update(
            """insert into platform.tenant_branding
                   (tenant_id, display_name, primary_color, secondary_color, on_primary, logo_key, favicon_key)
               values (?, ?, ?, ?, ?, ?, ?)
               on conflict (tenant_id) do update set
                   display_name = excluded.display_name,
                   primary_color = excluded.primary_color,
                   secondary_color = excluded.secondary_color,
                   on_primary = excluded.on_primary,
                   logo_key = excluded.logo_key,
                   favicon_key = excluded.favicon_key,
                   updated_at = now()""",
            clinicId,
            branding.displayName,
            branding.primary.value,
            branding.secondary.value,
            branding.onPrimary.value,
            branding.logoKey,
            branding.faviconKey,
        )
    }
}
