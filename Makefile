.PHONY: contracts anvil_image otterscan_image start_containers stop_containers

contracts:
	./gradlew compileContractsAndGenerateWrappers

anvil_image:
	cd docker/anvil && docker build -t anvil -f ./Dockerfile .&& cd ../..

otterscan_image:
	cd docker/otterscan && docker build -t otterscan -f ./Dockerfile .&& cd ../..

stop_containers:
	docker compose down --remove-orphans
	cd contracts && make stop_containers && cd ..

start_containers: stop_containers
	docker compose down --remove-orphans && docker compose up -d
	cd contracts && make start_containers && cd ..

stop_arch_containers:
	cd contracts && make stop_containers && cd ..

start_arch_containers:
	cd contracts && make start_containers && cd ..

start_ci_containers: stop_containers
	docker compose -f ./docker-compose-ci.yaml down --remove-orphans && docker compose -f ./docker-compose-ci.yaml up -d
	cd contracts && make start_ci_containers && cd ..

anvil_logs:
	docker compose logs anvil -f

local_containers:
	./gradlew jibDockerBuild

publish_containers:
	AWS_SDK_LOAD_CONFIG=true ./gradlew jib

db_migrate:
	./gradlew :run --args="db:migrate"

db_seed:
	./gradlew :run --args="db:seed"

local_init: start_containers contracts db_migrate db_seed

lint:
	./gradlew lintKotlin && (cd web-ui && pnpm run lint) && (cd telegram-mini-app && pnpm run lint)

format:
	./gradlew formatKotlin && (cd web-ui && pnpm run lint --fix) && (cd telegram-mini-app && pnpm run lint --fix) && (cd terraform && terraform fmt -recursive)

test:
	./gradlew test

run_backend:
	./gradlew :run

run_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run

run_only_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run --args=sequencer

run_not_sequencer:
	SANDBOX_MODE=true ./gradlew :sequencer:run --args=not-sequencer


run_mocker:
	FAUCET_POSSIBLE=1 MARKETS=BTC:1337/ETH:1337 BTC_1337_ETH_1337_PRICE_BASELINE=17.5 BTC_1337_ETH_1337_INITIAL_BASE_BALANCE=1 API_URL=http://localhost:9000 ./gradlew :mocker:run

run_telegrambot:
	API_URL=http://localhost:9000 ./gradlew :telegrambot:run

run_ui:
	cd web-ui && pnpm install && pnpm run dev

