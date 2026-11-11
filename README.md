# BGH Smart Control Binding para openHAB 5.x

Control local por LAN de aires acondicionados BGH Smart Control (protocolo Solidmation), sin la nube y
sin broker MQTT. Los aires se descubren solos en el Inbox de openHAB.

El protocolo está documentado en [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

> Proyecto independiente: no está afiliado a BGH ni a Solidmation, ni forma parte de openHAB oficial.

## Compilar

Requiere JDK 21 y Maven 3.9+. Las dependencias de openHAB se bajan de `openhab.jfrog.io`.

```
mvn package
```

Genera `target/org.openhab.binding.bgh-<versión>.jar`.

## Instalar

1. Copiar el `.jar` a la carpeta `addons/` de openHAB (`/usr/share/openhab/addons/` en Debian/Ubuntu/openHABian,
   `/openhab/addons/` en Docker). openHAB lo carga solo, sin reiniciar.
2. Verificar en la consola de Karaf: `bundle:list | grep -i bgh` (estado `Active`).
3. Logs: `log:set DEBUG org.openhab.binding.bgh` (o `TRACE` para ver cada paquete).

### Requisitos de red

- openHAB tiene que estar en la misma red/VLAN que los aires: el descubrimiento es un broadcast UDP.
- Puertos UDP 20910 (aires) y 20911 (local, en el host de openHAB).
- Nada más puede usar el puerto 20911 en ese host (por ejemplo, otro cliente del protocolo).
  Si está ocupado, los Things quedan OFFLINE con el mensaje "No se pudo abrir el puerto UDP 20911".
- **Docker**: el contenedor necesita `network_mode: host`; con red bridge no llegan los broadcasts.

## Things

Los aires aparecen solos en el Inbox (búsqueda en segundo plano cada 5 minutos, o "Scan" en la UI).
Cada uno se identifica por su MAC, así que la IP puede cambiar por DHCP sin perder el Thing.

Configuración manual (`conf/things/bgh.things`):

```
Thing bgh:ac:living     "Aire living"     [ macAddress="AC:CF:23:12:34:56", ipAddress="192.168.1.50" ]
Thing bgh:ac:dormitorio "Aire dormitorio" [ macAddress="AC:CF:23:65:43:21" ]
```

| Parámetro | Por defecto | Descripción |
|---|---|---|
| `macAddress` | (obligatorio) | MAC del equipo |
| `ipAddress` | vacío | IP del equipo; vacía = se busca por broadcast |
| `refreshInterval` | 30 | Segundos entre consultas de estado |
| `subscribeInterval` | 60 | Segundos entre renovaciones de la suscripción a cambios |
| `endpoint` | 1 | Endpoint HVAC del protocolo (1 en el BGH-7032) |

El Thing pasa a OFFLINE si el equipo no responde durante 3 consultas seguidas, y en ese estado se manda un
broadcast en cada consulta para volver a ubicarlo.

## Canales

| Canal | Tipo | Acceso | Valores |
|---|---|---|---|
| `power` | Switch | lectura/escritura | ON / OFF (ON vuelve al último modo usado) |
| `mode` | String | lectura/escritura | `off`, `cool`, `heat`, `dry`, `fan`, `auto` |
| `fanSpeed` | String | lectura/escritura | `low`, `mid`, `high`, `auto` |
| `targetTemperature` | Number:Temperature | lectura/escritura | 17 a 30 °C |
| `currentTemperature` | Number:Temperature | lectura | temperatura medida por el equipo |
| `turbo` | Switch | lectura/escritura | ON / OFF (ver nota) |
| `swing` | Switch | lectura/escritura | ON / OFF: oscilación de la aleta que sube y baja (ver nota) |

**Turbo y swing:** el equipo no informa su estado, y cada orden que recibe alterna la función. El binding recuerda
el último valor que mandó desde openHAB y solo envía la orden cuando el valor pedido es distinto. Si se cambian con
el control remoto, o al reiniciar openHAB (arranca en OFF), el estado mostrado puede quedar desfasado: tocar el
interruptor dos veces lo vuelve a sincronizar. El swing se mantiene aunque se apague el aire.

Ejemplo de items (`conf/items/bgh.items`), con el aire como equipo dentro de una ubicación del modelo semántico:

```
Group              Living           "Living"                   <sofa>        ["LivingRoom"]
Group              gAireLiving      "Aire living"              <climate>     (Living)      ["HVAC"]
Switch             AireLiving_Power "Encendido"                <switch>      (gAireLiving) ["Switch", "Power"]             { channel="bgh:ac:living:power" }
String             AireLiving_Modo  "Modo [%s]"                <climate>     (gAireLiving) ["Control"]                     { channel="bgh:ac:living:mode" }
String             AireLiving_Fan   "Ventilador [%s]"          <fan>         (gAireLiving) ["Control"]                     { channel="bgh:ac:living:fanSpeed" }
Number:Temperature AireLiving_Obj   "Objetivo [%.0f %unit%]"   <temperature> (gAireLiving) ["Setpoint", "Temperature"]     { channel="bgh:ac:living:targetTemperature", unit="°C" }
Number:Temperature AireLiving_Temp  "Ambiente [%.1f %unit%]"   <temperature> (gAireLiving) ["Measurement", "Temperature"]  { channel="bgh:ac:living:currentTemperature", unit="°C" }
Switch             AireLiving_Turbo "Turbo"                    <fan>         (gAireLiving) ["Switch"]                      { channel="bgh:ac:living:turbo" }
Switch             AireLiving_Swing "Swing"                    <flow>        (gAireLiving) ["Switch"]                      { channel="bgh:ac:living:swing" }
```

Ejemplo de sitemap (`conf/sitemaps/aires.sitemap`):

```
sitemap aires label="Aires" {
    Frame label="Living" {
        Switch    item=AireLiving_Power
        Selection item=AireLiving_Modo mappings=[off="Apagado", cool="Frío", heat="Calor", dry="Seco", fan="Ventilación", auto="Automático"]
        Selection item=AireLiving_Fan  mappings=[low="Baja", mid="Media", high="Alta", auto="Automática"]
        Setpoint  item=AireLiving_Obj minValue=17 maxValue=30 step=1
        Text      item=AireLiving_Temp
        Switch    item=AireLiving_Turbo
        Switch    item=AireLiving_Swing
    }
}
```

## Estado

| Función | Estado |
|---|---|
| Descubrimiento, lectura de todos los canales, encendido/apagado, `cool`, `heat`, ventilador y setpoint | Verificado en openHAB 5.2.1 con tres BGH-7032 (fw 1.0.0.0) |
| `dry`, `fan`, `auto` | Implementado, sin probar en el equipo |
| Turbo y swing de la aleta que sube y baja | Verificado en BGH-7032 con `cmd_HVAC_Command`; el estado lo lleva el binding |
| Swing lateral | No soportado: el BGH-7032 no tiene esa aleta |
| Intervalo de renovación de la suscripción | Valor propio (60 s); el de la app es desconocido |

## Licencia

[EPL-2.0](LICENSE), la misma que usa openHAB.
