# Prime Best Bid / Ask Java Reference Application

A minimal Java console application that connects to the Coinbase Prime WebSocket (`l2_data` channel) and prints the best bid & ask every time the book updates (every ~500 ms).

---
# Quickstart

## 1. Clone the repo
```
git clone https://github.com/jc-cb/prime-best-bid-ask-java.git
cd prime-best-bid-ask-java
```

## 2. Set the four required environment variables

```
export API_KEY=xxx
export SECRET_KEY=xxx
export PASSPHRASE=xxx
export SVC_ACCOUNTID=xxx
```

## 3. Build the JAR
```
mvn clean package              # produces target/prime-best-bid-ask-java-1.0.0.jar
```

## 4. Run
```
java -jar target/prime-best-bid-ask-java-1.0.0.jar
```

You should see output like this:

```
Best Bid: 3070.12 (qty 5.031000) | Best Ask: 3070.23 (qty 2.114000)
Best Bid: 3070.15 (qty 3.100000) | Best Ask: 3070.25 (qty 1.514000)
```