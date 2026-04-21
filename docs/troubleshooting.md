# Troubleshooting

## App shows no battery updates

1. Confirm Bluetooth is enabled.
2. Confirm runtime permissions are granted.
3. Open the case and keep earbuds near the phone.
4. Tap refresh again.

## Total battery is `N/A`

- This means no successful `2A19` read has happened yet.
- Check connection stability and retry refresh.

## Split battery remains `--`

- The app keeps old values and only updates on successful split frame reception.
- If no split frame arrives in this refresh cycle, previous values remain.

## Export logs

- Use the in-app export button.
- Logs are written to `Download` via MediaStore.
