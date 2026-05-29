export KUBECONFIG=./kubeconfig-onprem:./kubeconfig-cloud

oc --context onprem rollout restart deployment/account-service -n banking-demo
oc --context onprem rollout restart deployment/cluster-gateway -n banking-demo
oc --context onprem rollout restart deployment/dashboard-backend -n banking-demo
oc --context onprem rollout restart deployment/dashboard-frontend -n banking-demo
oc --context onprem rollout restart deployment/ledger-service -n banking-demo
oc --context onprem rollout restart deployment/transaction-generator -n banking-demo
oc --context onprem rollout restart deployment/transaction-processor -n banking-demo

oc --context cloud rollout restart deployment/account-service -n banking-demo
oc --context cloud rollout restart deployment/cluster-gateway -n banking-demo
oc --context cloud rollout restart deployment/ledger-service -n banking-demo
oc --context cloud rollout restart deployment/transaction-generator -n banking-demo
oc --context cloud rollout restart deployment/transaction-processor -n banking-demo
