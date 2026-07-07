-- Script de inicialização do PostgreSQL
-- Cria os schemas para cada microserviço

-- Schema para Auth Service
CREATE SCHEMA IF NOT EXISTS auth_schema;
COMMENT ON SCHEMA auth_schema IS 'Schema para o serviço de autenticação';

-- Schema para Products Service
CREATE SCHEMA IF NOT EXISTS products_schema;
COMMENT ON SCHEMA products_schema IS 'Schema para o serviço de produtos';

-- Schema para Inventory Service
CREATE SCHEMA IF NOT EXISTS inventory_schema;
COMMENT ON SCHEMA inventory_schema IS 'Schema para o serviço de estoque';

-- Schema para Orders Service
CREATE SCHEMA IF NOT EXISTS orders_schema;
COMMENT ON SCHEMA orders_schema IS 'Schema para o serviço de pedidos';

-- Schema para Billing Service
CREATE SCHEMA IF NOT EXISTS billing_schema;
COMMENT ON SCHEMA billing_schema IS 'Schema para o serviço de pagamentos';

-- Schema para Notification Service
CREATE SCHEMA IF NOT EXISTS notification_schema;
COMMENT ON SCHEMA notification_schema IS 'Schema para o serviço de notificações';

-- Concede permissões para o usuário admin
GRANT ALL PRIVILEGES ON SCHEMA auth_schema TO admin;
GRANT ALL PRIVILEGES ON SCHEMA products_schema TO admin;
GRANT ALL PRIVILEGES ON SCHEMA inventory_schema TO admin;
GRANT ALL PRIVILEGES ON SCHEMA orders_schema TO admin;
GRANT ALL PRIVILEGES ON SCHEMA billing_schema TO admin;
GRANT ALL PRIVILEGES ON SCHEMA notification_schema TO admin;

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA auth_schema TO admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA products_schema TO admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA inventory_schema TO admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA orders_schema TO admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA billing_schema TO admin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA notification_schema TO admin;