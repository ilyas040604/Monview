
{
  "rules": {
    ".read": false,
    ".write": false,
    "sessions": {
      "$code": {
        ".read": "auth != null && (data.child('owner').val() === auth.uid || data.child('viewerUid').val() === auth.uid)",
        ".write": "auth != null && ((!data.exists() && newData.child('owner').val() === auth.uid && newData.child('status').val() === 'waiting' && newData.child('expiresAt').val() > now) || (data.exists() && data.child('owner').val() === auth.uid) || (data.exists() && data.child('status').val() === 'waiting' && data.child('expiresAt').val() > now && !data.child('viewerUid').exists() && newData.child('owner').val() === data.child('owner').val() && newData.child('viewerUid').val() === auth.uid && newData.child('status').val() === 'connected'))",
        ".validate": "newData.hasChildren(['owner', 'createdAt', 'expiresAt', 'status'])",
        "owner": {
          ".validate": "newData.isString()"
        },
        "viewerUid": {
          ".validate": "newData.isString()"
        },
        "createdAt": {
          ".validate": "newData.isNumber()"
        },
        "expiresAt": {
          ".validate": "newData.isNumber()"
        },
        "joinedAt": {
          ".validate": "newData.isNumber()"
        },
        "status": {
          ".validate": "newData.val() === 'waiting' || newData.val() === 'connected'"
        },
        "signals": {
          "offer": {
            ".write": "auth != null && root.child('sessions').child($code).child('owner').val() === auth.uid"
          },
          "answer": {
            ".write": "auth != null && root.child('sessions').child($code).child('viewerUid').val() === auth.uid"
          },
          "cameraCandidates": {
            "$candidateId": {
              ".write": "auth != null && root.child('sessions').child($code).child('owner').val() === auth.uid"
            }
          },
          "viewerCandidates": {
            "$candidateId": {
              ".write": "auth != null && root.child('sessions').child($code).child('viewerUid').val() === auth.uid"
            }
          }
        }
      }
    }
  }
}
