# Protocolo LAN de BGH Smart Control (Solidmation)

Derivado por análisis estático de `Smart_Control_com.solidmation.bghsmartcontrol_v2.7_(51).xapk`
(ensamblado `SolidApp.dll`, namespace `Solidmation.Mobile.Core.UDPHandling`) y verificado contra
equipos reales: ver "Estado de verificación".

La app Android ya implementa control directo por LAN. Si conoce la IP del equipo
(`LocalIPAddress`), envía los comandos por UDP y espera un ACK; solo si eso falla cae al cloud
(`HomeCloudCommandService`). Es decir, el firmware **no necesita el cloud para aceptar comandos**.

## Transporte

| Dato | Valor | Origen |
|---|---|---|
| Puerto UDP del equipo | **20910** | `UDPManagerBase.get_PortClient` |
| Puerto UDP local de la app (escucha) | **20911** | `UDPManagerBase.get_PortServer` |
| Broadcast | `255.255.255.255` | `UDPManagerBase.get_BroadcastIPAddress` |
| MAC "ninguna" (origen) | `00:00:00:00:00:00` | `new byte[6]` en `UDPManagerBase..ctor` |
| MAC broadcast (destino) | `FF:FF:FF:FF:FF:FF` | datos estáticos de `MAC_BROADCAST` |

La app envía y recibe con el mismo socket (bind 20911). Las respuestas llegan a ese puerto.
No se observa cifrado, firma ni clave en las rutas de `SetMode`, `Poll` ni `Subscribe`.

## Paquete

`paquete = cabecera (17 bytes) + payload`. Todo en bytes; sin framing adicional.

| Offset | Tamaño | Campo |
|---|---|---|
| 0 | 1 | `ProtocolVersion` (la app envía 0) |
| 1 | 6 | `SourceMAC` (la app envía ceros) |
| 7 | 6 | `DestinationMAC` (MAC del equipo, o FF×6 en broadcast) |
| 13 | 1 | `SequenceNumber` (aleatorio 0-255) |
| 14 | 1 | `SourceEndpoint` (la app envía 0) |
| 15 | 1 | `DestinationEndpoint` (endpoint del equipo) |
| 16 | 1 | `CommandID` |

Fuente: `HACommandHeader.GetBytes/SetBytes`, `get_HeaderOffset = 17`.

## CommandID (`HACommand_Constants`)

| Valor | Nombre |
|---|---|
| 0x80 / 0x81 | `Rsp_ACK` / `Rsp_NAK` |
| 0x82 | `Rsp_JOINED` |
| 0xA1 | `Join` (primer byte del payload = subcomando) |
| 0xA2 | `Custom_Command` |
| 0xA4 | `Reboot` |
| 0xA5 | `Upgrade` |
| 0xAF | `Leave` |
| 0xE0 / 0xF0 | `Ping` / `Ping_Reply` |
| 0xE4 | `Poll` |
| 0xF5 | `Debug` |
| 0xFD | `StatusReport` |
| **0x61** | **`cmd_HVAC_SetMode`** |
| **0x62** | **`cmd_HVAC_Command`** |

Subcomandos de `Join` (0xA1), `HACommand_Join_Constants`: Enumerate=1, Enumerate_Response=2, Join=3,
**Enumerate_All=4**, Scan=5, Scan_Response=6, Get_Cloud_Notification_IP=7, Set_Cloud_Notification_IP=8,
Key_Request=9, Set_Key=0x0A, **Subscribe=0x0C**, Unsubscribe=0x0D.

## Operaciones

### Descubrir (`Send_Cmd_Join_Enum`)
Broadcast a `255.255.255.255:20910`. Destino MAC = FF×6, endpoints 0, `CommandID=0xA1`, payload `[0x04]`.
La respuesta trae en el payload un string UTF-8 `"modelo,firmware"` (`HACommand_Join_EnumResponse`).
La IP del equipo es la IP origen del datagrama de respuesta y su MAC va en `SourceMAC` de la cabecera.

### Suscribirse a cambios (`Send_Cmd_Subscribe`)
Unicast a la IP del equipo. `CommandID=0xA1`, payload `[0x0C]`, endpoint destino 0.
La app renueva la suscripción periódicamente (`RunSubribeRenewal`); el intervalo no se determinó.

### Consultar estado (`Send_Cmd_Poll`)
Unicast. `CommandID=0xE4`, sin payload, endpoint destino = endpoint HVAC.
El equipo responde con `StatusReport` (0xFD).

### Estado (`CommandID=0xFD`, `HACommand_Status` + `HACommand_StatusHVAC`)
Payload: `[StatusType]` + datos. `StatusType`: Light=1, Curtain=4, **HVAC=6**, Doorlock=7, IAS=8.
Para HVAC, 11 bytes tras `StatusType`:

| Byte | Campo (`EndpointValueType`) | Codificación |
|---|---|---|
| 0 | `ThermostatMode` (14) | ver modos |
| 1 | `ThermostatFanMode` (15) | ver ventilador |
| 2 | `HVACFlags` (18) | bits abajo |
| 3-4 | `MeasuredTemperatureC` (13) | int16 little-endian ÷ 100 |
| 5-6 | `ThermostatDesiredTempC` (20) | int16 little-endian ÷ 100 |
| 7-8 | `HVACTimerOnMinutes` (16) | uint16 LE |
| 9-10 | `HVACTimerOffMinutes` (17) | uint16 LE |

### Fijar modo/temperatura (`ThermostatSetMode`)
Unicast. `CommandID=0x61`, endpoint destino = endpoint HVAC. Payload de 5 bytes:

`[Mode, FanMode, Flags, TempLo, TempHi]` con `Temp = round(|°C| × 100)` en uint16 little-endian
(`WORDToPortable16`: `b0 = v & 0xFF`, `b1 = v >> 8`). Ejemplo: 24 °C → 2400 → `60 09`.
La app espera un `Rsp_ACK` (0x80) y, si no llega, reintenta por cloud.

**Modo** (de `EndpointControlThermostatView`): Off=0, Cool=1, Heat=2, Dry=3, Fan=4, Auto=0xFE.
(En `Command_ThermostatSetModeLastOrOff`, 253 parece "volver al último modo" y 255 "sin cambio": **inferido**.)
**Ventilador**: Low=1, Mid=2, High=3, Auto=0xFE.
**Flags (`HVACFlags`)**: 0x08 Turbo, 0x10 swing horizontal, 0x20 swing vertical.

### Comando HVAC suelto (`SendHVACCommand`)
Unicast. `CommandID=0x62`, payload de 1 byte. La app lo usa para swing/turbo en equipos con
`SetSwingModesViaHVACCustomCommand`: bit 0x10 → `0x51`, bit 0x20 → `0x61`, bit 0x08 → `0x71`.

En el BGH-7032 (verificado el 2026-11-10):

| Código | Efecto |
|---|---|
| `0x51` | Alterna la oscilación de la aleta que sube y baja (lo que la app llama "horizontal": la aleta es horizontal) |
| `0x61` | Pita y el display muestra `on`/`oF` un segundo, sin efecto visible: probablemente el swing lateral, que este equipo no tiene |
| `0x71` | Alterna el turbo |

Cada envío responde ACK y **alterna** la función; no hay códigos separados de encendido y apagado.
El `StatusReport` no lo refleja (el byte de flags sigue en 0), así que quien controla el equipo tiene que llevar el
estado. La oscilación sobrevive a un apagado y encendido por `SetMode`, y a un cambio de setpoint.
Los flags 0x08/0x10/0x20 de `SetMode` no tienen efecto en este modelo.

Se probaron los 256 valores del byte: solo `0x51`, `0x61` y `0x71` reciben ACK; el resto el equipo los ignora
sin ACK ni NAK. En particular, **no hay código para apagar la luz del display**: la app tampoco lo ofrece (su
`BacklightTimeout` pertenece a `HACommand_ThermostatSetup`, la configuración de los termostatos de pared de
Solidmation, no de los aires).

## Estado de verificación

Probado el 2026-10-16 contra un BGH-7032 (firmware 1.0.0.0) en la LAN, solo lectura (`discover` y `poll`):

| Elemento | Estado |
|---|---|
| Puertos 20910/20911, cabecera de 17 bytes, broadcast de descubrimiento | **Verificado en equipo real** |
| Respuesta a Enumerate_All: `CommandID 0xA1`, payload `02` + texto UTF-8 `BGH-7032,1.0.0.0` | **Verificado** (el primer byte es el subcomando `Enumerate_Response`) |
| Endpoint HVAC = **1** (endpoint 0 y 2 responden NAK `0x81`) | **Verificado** |
| `Poll` (0xE4) devuelve `StatusReport` 0xFD y el layout HVAC de 11 bytes | **Verificado** (apagado, fan 2, setpoint 26 °C, medida 21 °C; falta contrastar con el display) |
| El equipo también emite `CommandID 0xF5` (texto de debug, p. ej. `>> OFF 26° ,`) y `0xF4` (payload `06 00`/`06 02`, sin interpretar) | Observado, `0xF4` desconocido |
| Cada respuesta llega duplicada | Observado; deduplicar por `SequenceNumber` |
| `SetMode` (0x61) con modo `cool`, fan `low`, 24 °C: ACK `0x80` y estado posterior `mode=1 fan=1 desired=24.0` | **Verificado** |
| `SetMode` con modo `off`, fan `mid`, 26 °C: ACK y estado `mode=0 fan=2 desired=26.0` | **Verificado** (restauró el estado previo) |
| Tras un `SetMode` el equipo emite un `0xF5` de debug (`>> COOL 24° ,`) y un `0xFD` espontáneo con el nuevo estado, sin necesidad de `Poll` | Observado |
| Cuenta cloud necesaria para operar | **No**: discover, poll y set funcionaron solo por LAN |
| `SetMode` con modo `heat` (desde el binding de openHAB, 2026-11-10) | **Verificado**: ACK y estado `mode=2` |
| Varios equipos en la misma LAN (tres BGH-7032) | **Verificado**: cada uno responde a su MAC |
| El equipo emite `CommandID 0xFB` (payload de 5 bytes, p. ej. `01 a4 a7 00 00`) | Observado, sin interpretar |
| Intervalo de renovación de Subscribe | Desconocido |
| Semántica de 253/255 | Inferida |
| Flags de swing/turbo en `SetMode` | **Sin efecto** en el BGH-7032 (ACK, pero no cambia nada) |
| Comando `0x62` con `0x51` (swing) y `0x71` (turbo) | **Verificado**: cada envío alterna la función; el estado no lo informa |
| Cifrado/clave en `Key_Request`/`Set_Key` | No usado en estas rutas; no analizado |

## Cómo se obtuvo

Por análisis estático de la app: se desempaquetó `assemblies.blob` de Xamarin (XABA v1 + LZ4) y se
leyeron el IL y las constantes de `SolidApp.dll`. Este repositorio no incluye la app ni código derivado
de ella; solo la descripción del protocolo, para interoperar con equipos propios.
