#!/bin/bash
# Creates each service's database and its own user, which can reach only that database.
# The MySQL image runs this once, on the first start with an empty data volume (docker compose down -v starts over).
# The passwords come from .env, through the environment of the mysql service in docker-compose.yml.

create_database() {
    local database=$1 user=$2 password=$3
    if [ -z "$password" ]; then
        echo "No password for $user: set it in .env" >&2
        exit 1
    fi
    # Inside a quoted SQL string, a backslash and a single quote have to be doubled
    password=${password//\\/\\\\}
    password=${password//\'/\'\'}
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root <<SQL
CREATE DATABASE \`$database\`;
CREATE USER '$user'@'%' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON \`$database\`.* TO '$user'@'%';
SQL
    echo "Created $database, reachable by $user"
}

create_database product_db ProductService_user "${PRODUCT_DB_PASSWORD:-}"
create_database user_db UserAuthService_user "${USER_DB_PASSWORD:-}"
create_database order_db OrderService_user "${ORDER_DB_PASSWORD:-}"
create_database payment_db PaymentService_user "${PAYMENT_DB_PASSWORD:-}"
