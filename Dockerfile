FROM eclipse-temurin:21

RUN apt-get update -y && apt-get install -y libcurl4-openssl-dev libcjson-dev unzip && curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install
RUN aws s3 COPY s3://lisk-envs/dshackle.yaml ./ 