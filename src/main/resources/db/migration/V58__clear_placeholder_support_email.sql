-- Clear the old placeholder support address from the singleton site content row.
-- Real support contacts are now supplied through APP_SUPPORT_EMAIL / app.brand.support-email
-- and should not be invented in seed data.

UPDATE site_content
SET contact_email = NULL
WHERE contact_email = 'support@ecopay.kz';
