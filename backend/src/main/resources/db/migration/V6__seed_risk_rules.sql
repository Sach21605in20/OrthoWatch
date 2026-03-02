-- Seed 5 default risk rules for the risk scoring engine
-- References admin user from V3 as created_by

INSERT INTO risk_rules (rule_name, condition_expression, risk_level, weight, is_active, version, created_by, created_at, updated_at)
VALUES
  ('FEVER_HIGH', 'FEVER_ABOVE_100', 'HIGH', 30, true, 1,
   (SELECT id FROM users WHERE email = 'admin@orthowatch.com'), NOW(), NOW()),

  ('DVT_SYMPTOMS', 'DVT_ANY_PRESENT', 'HIGH', 30, true, 1,
   (SELECT id FROM users WHERE email = 'admin@orthowatch.com'), NOW(), NOW()),

  ('PAIN_SPIKE', 'PAIN_SPIKE_GT_2', 'MEDIUM', 15, true, 1,
   (SELECT id FROM users WHERE email = 'admin@orthowatch.com'), NOW(), NOW()),

  ('SWELLING_TREND', 'SWELLING_INCREASING_2D', 'MEDIUM', 15, true, 1,
   (SELECT id FROM users WHERE email = 'admin@orthowatch.com'), NOW(), NOW()),

  ('WOUND_CONCERN', 'WOUND_REDNESS_DISCHARGE', 'HIGH', 25, true, 1,
   (SELECT id FROM users WHERE email = 'admin@orthowatch.com'), NOW(), NOW())

ON CONFLICT (rule_name) DO NOTHING;
