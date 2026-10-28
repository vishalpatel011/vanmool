#!/bin/bash
# Creates each service's database and its own user, which can reach only that database.
# The MySQL image runs this once, on the first start with an empty data volume (docker compose down -v starts over).
# The passwords come from .env, through the environment of the mysql service in docker-compose.yml.
#
# The same script prepares an external server such as RDS, run from a MySQL client container:
#   docker run --rm --env-file .env -e MYSQL_HOST=<server> -e MYSQL_ADMIN_USER=<admin> -e MYSQL_ADMIN_PASSWORD=<password> \
#     -v ./infrastructure/mysql/init:/init:ro mysql:8.4 bash /init/create-databases.sh
# Running it again changes nothing: what exists is left as it is.

create_database() {
    local database=$1 user=$2 password=$3
    if [ -z "$password" ]; then
        echo "No password for $user: set it in .env" >&2
        exit 1
    fi
    # Inside a quoted SQL string, a backslash and a single quote have to be doubled
    password=${password//\\/\\\\}
    password=${password//\'/\'\'}
    MYSQL_PWD="${MYSQL_ADMIN_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mysql --user="${MYSQL_ADMIN_USER:-root}" \
        ${MYSQL_HOST:+--host="$MYSQL_HOST"} <<SQL
CREATE DATABASE IF NOT EXISTS \`$database\`;
CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON \`$database\`.* TO '$user'@'%';
SQL
    echo "$database ready, reachable by $user"
}

create_database product_db ProductService_user "${PRODUCT_DB_PASSWORD:-}"
create_database user_db UserAuthService_user "${USER_DB_PASSWORD:-}"
create_database order_db OrderService_user "${ORDER_DB_PASSWORD:-}"
create_database payment_db PaymentService_user "${PAYMENT_DB_PASSWORD:-}"
