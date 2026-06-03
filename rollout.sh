# Force a rollout restart of all deployments in both clusters to ensure they pick up any new configuration or image changes.
# ArgoCD will automatically detect the changes and sync the applications accordingly, but it will take a few moments for the new pods to be created and become ready. You can monitor the rollout status using `oc rollout status` for each deployment.

export KUBECONFIG=./kubeconfig-onprem:./kubeconfig-cloud

echo "Restarting deployments in on-prem cluster..."
oc --context onprem rollout restart deployment/account-service -n banking-demo
oc --context onprem rollout restart deployment/cluster-gateway -n banking-demo
oc --context onprem rollout restart deployment/dashboard-backend -n banking-demo
oc --context onprem rollout restart deployment/dashboard-frontend -n banking-demo
oc --context onprem rollout restart deployment/ledger-service -n banking-demo
oc --context onprem rollout restart deployment/transaction-generator -n banking-demo
oc --context onprem rollout restart deployment/transaction-processor -n banking-demo

echo "Restarting deployments in cloud cluster..."
oc --context cloud rollout restart deployment/account-service -n banking-demo
oc --context cloud rollout restart deployment/cluster-gateway -n banking-demo
oc --context cloud rollout restart deployment/ledger-service -n banking-demo
oc --context cloud rollout restart deployment/transaction-generator -n banking-demo
oc --context cloud rollout restart deployment/transaction-processor -n banking-demo
