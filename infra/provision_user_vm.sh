#!/usr/bin/env bash
# provision_user_vm.sh -- per-subscriber "내 클라우드 컴퓨터" for 메이트 (Mate) for Gleo.
#
# ONE VM PER SUBSCRIBER = ISOLATION. Each user gets their own EC2 instance, their
# own security group, their own bearer token and their own encrypted disk. The
# car never runs the agent; it only calls https://<user>.vm.../v1/jobs.
#
# NOTHING IN THIS FILE WAS RUN FOR THE CONTEST DEMO. No paid cloud resource was
# created. In the demo a Mac Studio stands in for the VM. This is the service path.
#
# DEFAULT IS DRY RUN. Without --apply the script only prints the plan and, if the
# aws cli and credentials are present, asks EC2 to validate the launch with
# `run-instances --dry-run` (which never creates anything).
#
# Usage:
#   provision_user_vm.sh <user-id> [--plan basic|pro|team] [--region ap-northeast-2] [--apply]
#   provision_user_vm.sh <user-id> --destroy [--region ...] [--apply]   # unsubscribe: delete VM + data
#   --offline   never call aws at all, print commands only (dry run only)
#
# Assumed to exist already (not created here):
#   - IAM instance profile `mate-vm-role` (override: MATE_INSTANCE_PROFILE) with
#     AmazonSSMManagedInstanceCore + ssm:GetParameter on
#     arn:aws:ssm:*:*:parameter/mate/users/<user-id>/*
#     NOTE: for real isolation that policy must be scoped per user (one role per
#     user, or ABAC); a single shared role over /mate/users/* is NOT enough.
#   - The subscriber's OWN AI subscription (Claude / ChatGPT). After the VM is up the
#     subscriber logs the official agent CLI in on the VM once (e.g. `claude login`).
#     Mate never holds or resells model usage; there is no API key in this path.
#   - DNS zone for MATE_VM_DOMAIN (default vm.mate.example, a placeholder). Set
#     MATE_HOSTED_ZONE_ID to have the A record upserted in Route 53.
# Env: MATE_REPO_URL (required for --apply), MATE_VPC_ID, MATE_SUBNET_ID.
set -euo pipefail
export AWS_PAGER=""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLOUD_INIT="$SCRIPT_DIR/cloud-init.yaml"
AMI_PARAM="/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
PROFILE="${MATE_INSTANCE_PROFILE:-mate-vm-role}"
DOMAIN="${MATE_VM_DOMAIN:-vm.mate.example}"
REPO_URL="${MATE_REPO_URL:-__REPO_URL__}"
SUBNET_ID="${MATE_SUBNET_ID:-}"

USER_ID=""; PLAN="basic"; REGION="ap-northeast-2"; APPLY=0; MODE="create"; OFFLINE=0

usage() { sed -n '15,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
die()   { echo "error: $*" >&2; exit 1; }
show()  { printf '  $'; printf ' %q' "$@"; printf '\n'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --plan)    PLAN="${2:?--plan needs a value}"; shift 2 ;;
    --region)  REGION="${2:?--region needs a value}"; shift 2 ;;
    --apply)   APPLY=1; shift ;;
    --destroy) MODE="destroy"; shift ;;
    --offline) OFFLINE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)        die "unknown option: $1" ;;
    *)         [ -z "$USER_ID" ] || die "unexpected argument: $1"; USER_ID="$1"; shift ;;
  esac
done

[ -n "$USER_ID" ] || { usage >&2; exit 2; }
# user id becomes a DNS label, a tag value and an SSM path segment: keep it strict.
ID_RE='^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$'
[[ "$USER_ID" =~ $ID_RE ]] || die "user-id must match $ID_RE"
REGION_RE='^[a-z]{2}(-[a-z]+)+-[0-9]+$'
[[ "$REGION" =~ $REGION_RE ]] || die "bad region: $REGION"
[ "$APPLY" -eq 0 ] || [ "$OFFLINE" -eq 0 ] || die "--apply and --offline are mutually exclusive"

case "$PLAN" in
  basic) ITYPE="t4g.small";  DISK_GB=30 ;;
  pro)   ITYPE="t4g.large";  DISK_GB=60 ;;
  team)  ITYPE="t4g.xlarge"; DISK_GB=120 ;;
  *)     die "plan must be basic|pro|team" ;;
esac

SG_NAME="mate-vm-$USER_ID"
PARAM_PREFIX="/mate/users/$USER_ID"
HOST="$USER_ID.$DOMAIN"
TAG_FILTER="Name=tag:mate:user,Values=$USER_ID"
LIVE_FILTER="Name=instance-state-name,Values=pending,running,stopping,stopped"
UD="<rendered cloud-init.yaml>"

a() { aws --region "$REGION" --output text "$@"; }
aws_ready() {
  [ "$OFFLINE" -eq 0 ] && command -v aws >/dev/null 2>&1 && aws sts get-caller-identity >/dev/null 2>&1
}

render_user_data() {
  [ -f "$CLOUD_INIT" ] || die "missing $CLOUD_INIT"
  UD="$(mktemp -t mate-userdata.XXXXXX)"
  trap 'rm -f "$UD"' EXIT
  sed -e "s|__USER_ID__|$USER_ID|g" -e "s|__REPO_URL__|$REPO_URL|g" \
      -e "s|__REGION__|$REGION|g" -e "s|vm\.mate\.example|$DOMAIN|g" "$CLOUD_INIT" > "$UD"
}

build_run_cmd() {  # $1 = security group id (empty in dry run: the group does not exist yet)
  RUN_CMD=(aws --region "$REGION" ec2 run-instances
    --image-id "resolve:ssm:$AMI_PARAM"
    --instance-type "$ITYPE" --count 1
    --iam-instance-profile "Name=$PROFILE"
    --metadata-options "HttpTokens=required,HttpEndpoint=enabled"
    --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=$DISK_GB,VolumeType=gp3,Encrypted=true,DeleteOnTermination=true}"
    --tag-specifications
      "ResourceType=instance,Tags=[{Key=Name,Value=$SG_NAME},{Key=mate:user,Value=$USER_ID},{Key=mate:plan,Value=$PLAN}]"
      "ResourceType=volume,Tags=[{Key=mate:user,Value=$USER_ID},{Key=mate:plan,Value=$PLAN}]"
    --user-data "file://$UD")
  if [ -n "${1:-}" ]; then RUN_CMD+=(--security-group-ids "$1"); fi
  if [ -n "$SUBNET_ID" ]; then RUN_CMD+=(--subnet-id "$SUBNET_ID"); fi
}

live_instances() { a ec2 describe-instances --filters "$TAG_FILTER" "$LIVE_FILTER" \
  --query 'Reservations[].Instances[].InstanceId' | tr '\t' ' '; }
sg_id() { a ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' 2>/dev/null | grep -v '^None$' || true; }

# ------------------------------------------------------------------ destroy --
destroy() {
  echo "== DESTROY user=$USER_ID region=$REGION  ($([ "$APPLY" -eq 1 ] && echo APPLY || echo 'DRY RUN'))"
  echo "   terminates the instance tagged mate:user=$USER_ID (encrypted root volume is"
  echo "   deleted with it), deletes $PARAM_PREFIX/* and the security group $SG_NAME."
  if ! aws_ready; then
    [ "$APPLY" -eq 0 ] || die "aws cli + credentials required for --apply"
    echo "-- aws cli/credentials not available (or --offline): commands only"
    show aws --region "$REGION" ec2 describe-instances --filters "$TAG_FILTER" "$LIVE_FILTER"
    show aws --region "$REGION" ec2 terminate-instances --instance-ids "<instance-id>"
    show aws --region "$REGION" ssm get-parameters-by-path --path "$PARAM_PREFIX" --recursive
    show aws --region "$REGION" ssm delete-parameters --names "$PARAM_PREFIX/token"
    show aws --region "$REGION" ec2 delete-security-group --group-id "<sg-id of $SG_NAME>"
    return 0
  fi
  local ids params sg
  ids="$(live_instances)"
  params="$(a ssm get-parameters-by-path --path "$PARAM_PREFIX" --recursive --query 'Parameters[].Name' | tr '\t' ' ')"
  sg="$(sg_id)"
  echo "   instances : ${ids:-<none>}"
  echo "   ssm params: ${params:-<none>}"
  echo "   sec. group: ${sg:-<none>}"
  if [ "$APPLY" -eq 0 ]; then
    echo "-- dry run: nothing deleted. Re-run with --apply to delete."
    return 0
  fi
  if [ -n "$ids" ]; then
    # shellcheck disable=SC2086
    a ec2 terminate-instances --instance-ids $ids >/dev/null
    # shellcheck disable=SC2086
    a ec2 wait instance-terminated --instance-ids $ids
  fi
  if [ -n "$params" ]; then
    # shellcheck disable=SC2086
    a ssm delete-parameters --names $params >/dev/null
  fi
  if [ -n "$sg" ]; then a ec2 delete-security-group --group-id "$sg" >/dev/null; fi
  echo "destroyed. Remember to remove the DNS record for $HOST."
}

# ------------------------------------------------------------------- create --
create() {
  echo "== PROVISION user=$USER_ID plan=$PLAN region=$REGION  ($([ "$APPLY" -eq 1 ] && echo APPLY || echo 'DRY RUN'))"
  echo "   instance : $ITYPE (arm64), Ubuntu 24.04 via $AMI_PARAM"
  echo "   disk     : ${DISK_GB} GB gp3, encrypted, deleted on termination"
  echo "   network  : security group $SG_NAME, inbound tcp/443 only (no SSH; SSM Session Manager)"
  echo "   identity : instance profile $PROFILE, IMDSv2 required"
  echo "   secrets  : $PARAM_PREFIX/token (generated, SecureString). AI: the subscriber's own subscription, logged in on the VM"
  echo "   tags     : mate:user=$USER_ID mate:plan=$PLAN"
  echo "   endpoint : https://$HOST/v1/jobs  (Authorization: Bearer <token>)"

  if [ "$APPLY" -eq 0 ]; then
    if aws_ready; then
      render_user_data; build_run_cmd ""
      echo "-- validating with EC2 (--dry-run, creates nothing):"
      show "${RUN_CMD[@]}" --dry-run
      local out
      out="$("${RUN_CMD[@]}" --dry-run 2>&1 || true)"
      case "$out" in
        *DryRunOperation*) echo "   OK: request would have succeeded." ;;
        *)                 echo "   EC2 says: $out" ;;
      esac
    else
      build_run_cmd ""
      echo "-- aws cli/credentials not available (or --offline): commands only"
      show aws --region "$REGION" ec2 create-security-group --group-name "$SG_NAME" --description "Mate VM $USER_ID: 443 only" --vpc-id "<vpc-id>"
      show aws --region "$REGION" ec2 authorize-security-group-ingress --group-id "<sg-id>" --protocol tcp --port 443 --cidr 0.0.0.0/0
      show aws --region "$REGION" ssm put-parameter --name "$PARAM_PREFIX/token" --type SecureString --value "<openssl rand -hex 32>" --overwrite
      show "${RUN_CMD[@]}" --security-group-ids "<sg-id>" --dry-run
    fi
    echo "-- dry run: nothing created. Re-run with --apply to provision."
    return 0
  fi

  aws_ready || die "aws cli + credentials required for --apply"
  [ "$REPO_URL" != "__REPO_URL__" ] || die "set MATE_REPO_URL to the git URL of the mate repo"
  command -v openssl >/dev/null 2>&1 || die "openssl required to generate the token"
  [ -z "$(live_instances)" ] || die "user $USER_ID already has a VM: $(live_instances)"

  # 1. security group: 443 only
  local vpc sg
  vpc="${MATE_VPC_ID:-$(a ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId')}"
  [ -n "$vpc" ] && [ "$vpc" != "None" ] || die "no default VPC; set MATE_VPC_ID and MATE_SUBNET_ID"
  sg="$(sg_id)"
  if [ -z "$sg" ]; then
    sg="$(a ec2 create-security-group --group-name "$SG_NAME" --vpc-id "$vpc" \
          --description "Mate VM $USER_ID: 443 only" --query GroupId)"
    a ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 443 --cidr 0.0.0.0/0 >/dev/null
  fi

  # 2. per-user token -> SSM SecureString (never printed)
  local token
  token="$(openssl rand -hex 32)"
  a ssm put-parameter --name "$PARAM_PREFIX/token" --type SecureString --value "$token" --overwrite >/dev/null
  unset token

  # 3. launch
  local iid ip
  render_user_data; build_run_cmd "$sg"
  iid="$("${RUN_CMD[@]}" --query 'Instances[0].InstanceId' --output text)"
  a ec2 wait instance-running --instance-ids "$iid"
  ip="$(a ec2 describe-instances --instance-ids "$iid" --query 'Reservations[0].Instances[0].PublicIpAddress')"

  # 4. DNS (optional). Caddy gets its certificate via TLS-ALPN-01 on 443 once this resolves.
  if [ -n "${MATE_HOSTED_ZONE_ID:-}" ]; then
    a route53 change-resource-record-sets --hosted-zone-id "$MATE_HOSTED_ZONE_ID" --change-batch \
      "{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{\"Name\":\"$HOST\",\"Type\":\"A\",\"TTL\":60,\"ResourceRecords\":[{\"Value\":\"$ip\"}]}}]}" >/dev/null
  else
    echo "note: point an A record $HOST -> $ip (MATE_HOSTED_ZONE_ID not set)"
  fi

  echo "instance : $iid ($ip)"
  echo "endpoint : https://$HOST/v1/jobs"
  echo "token    : aws ssm get-parameter --region $REGION --name $PARAM_PREFIX/token --with-decryption"
}

if [ "$MODE" = "destroy" ]; then destroy; else create; fi
