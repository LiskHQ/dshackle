all: build-foundation build-main

build-foundation:
	cd foundation && ../gradlew build publishToMavenLocal

build-main:
	./gradlew build

test: build-foundation
	./gradlew check

local-docker: Dockerfile
	docker build -t liskhq-dshackle .

jib: build-foundation local-docker
	./gradlew jib -Pdocker=liskhq

jib-docker: build-foundation local-docker
	./gradlew jibDockerBuild -Pdocker=liskhq

distZip: build-foundation
	./gradlew disZip

clean:
	./gradlew clean;
	cd foundation && ../gradlew clean
