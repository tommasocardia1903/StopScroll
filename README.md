# StopScroll

App Android che blocca **YouTube Shorts** e **Instagram Reels** a comando, e si ripara da sola quando YouTube o Instagram si aggiornano.

---

## 1. Primo avvio: farti compilare l'APK da GitHub (una volta sola, ~10 minuti)

1. Crea un account gratuito su **github.com**, se non ce l'hai.
2. In alto a destra: **+ → New repository**.
   - Nome: `stopscroll`
   - Visibilità: **Public** (serve perché l'app possa leggere le firme senza password)
   - Clicca **Create repository**.
3. Nella pagina del repository vuoto clicca **uploading an existing file**.
   Estrai lo zip sul computer e trascina **il contenuto** della cartella `StopScroll` (non la cartella stessa), poi **Commit changes**.
4. Controlla che esista la cartella `.github/workflows`. Su alcuni computer le cartelle che iniziano con il punto vengono saltate.
   Se manca: **Add file → Create new file**, scrivi come nome `.github/workflows/build.yml`, incolla il contenuto del file `build.yml` dallo zip e salva.
5. Vai nel tab **Actions**. Se te lo chiede, abilita i workflow. La compilazione **"Compila APK"** parte da sola; se non parte, aprila e premi **Run workflow**. Dura circa 5 minuti.
6. Quando ha la spunta verde, vai nella pagina principale del repository → **Releases** (colonna a destra) → scarica `StopScroll-vN.apk`.
   Il modo più comodo è aprire questa pagina direttamente dal browser del telefono.

## 2. Installazione sul telefono

1. Apri l'APK scaricato e conferma l'installazione. Se Android lo chiede, consenti al browser di installare app sconosciute.
2. Apri **StopScroll** e premi **Attiva il servizio di accessibilità** → cerca StopScroll → attivalo.
3. **Se l'interruttore è grigio** (Android 13 e successivi): torna nell'app, premi **Apri Info app** → menu **⋮** in alto a destra → **Consenti impostazioni con limitazioni**. Poi ripeti il punto 2.
4. Nella sezione **Aggiornamenti da GitHub** scrivi il tuo repository, ad esempio `tuonome/stopscroll`, e premi **Aggiorna firme ora**.
5. Consenti le notifiche quando l'app le chiede: servono per gli avvisi di riparazione.

### Il comando rapido
Abbassa la tendina delle notifiche → icona della matita (modifica) → trascina **Blocca Shorts** tra i pulsanti rapidi. Un tocco lo attiva o lo mette in pausa.

---

## 3. Come si ripara dopo gli aggiornamenti

Funziona su tre livelli, in automatico:

1. **Rilevamento a più segnali.** L'app riconosce Shorts e Reels dagli ID degli elementi, ma anche dalla struttura della schermata (pagina verticale a tutto schermo con i pulsanti like, commenti e condividi). Se un aggiornamento cambia gli ID, il riconoscimento di riserva di solito continua a bloccare.
2. **Avviso + "Insegna".** Quando l'app nota che sta bloccando solo con il riconoscimento di riserva, ti manda una notifica. Toccala: si apre l'app giusta con una bolla rossa in alto.
   - Passo 1: apri uno Short o un Reel a schermo intero e tocca la bolla.
   - Passo 2: torna alla Home e tocca la bolla.
   L'app confronta le due schermate e impara i nuovi ID. Tieni premuta la bolla per annullare.
   Puoi farlo anche a mano dai pulsanti **Insegna YouTube / Insegna Instagram**.
3. **Firme da GitHub.** Appena YouTube o Instagram si aggiornano, l'app scarica subito `config/signatures.json` dal tuo repository (e comunque ogni 12 ore).
   Per correggerlo basta modificare quel file direttamente sul sito di GitHub (icona della matita). Modificare le firme **non** ricompila l'app.

Se qualcosa non va, premi **Condividi diagnostica**: ottieni un testo con gli ID presenti a schermo, utile per correggere il file delle firme.

## 4. Aggiornare l'app stessa

Ogni volta che modifichi il codice su GitHub, la build riparte e crea una nuova release.
L'app controlla le release e ti avvisa con una notifica: toccala, scarica l'APK e installalo **sopra** quello vecchio. Le impostazioni restano.

---

## Note

- **Firma dell'APK**: la chiave `app/stopscroll.jks` è inclusa nel repository, così ogni versione si installa sopra la precedente. Essendo il repository pubblico, chiunque potrebbe firmare un APK "compatibile". Per un'app personale il rischio è basso: installa solo APK presi dalle tue Releases.
- **Privacy**: l'app legge lo schermo solo dentro YouTube e Instagram e non invia nulla. Si collega soltanto al tuo repository GitHub.
- **Limiti**: gli Shorts in anteprima nella Home di YouTube restano visibili, ma aprirli fa scattare il blocco. Un cambiamento radicale di YouTube o Instagram può richiedere una modifica al codice.
- **Android Studio** (facoltativo): il progetto si apre anche lì con *Open* sulla cartella. Le build locali usano la stessa chiave.

## Struttura

| File | Cosa fa |
|---|---|
| `BlockerService.kt` | Servizio di accessibilità: osserva lo schermo, blocca, rileva gli aggiornamenti delle app |
| `Detection.kt` | Istantanea dello schermo, punteggio a più segnali, apprendimento |
| `Overlays.kt` | Coperture sul pulsante Shorts/Reels e bolla della modalità "Insegna" |
| `Signatures.kt` | Unisce firme incluse, remote e imparate |
| `Updater.kt` | Scarica firme e controlla nuove versioni da GitHub; notifiche |
| `BlockTileService.kt` | Pulsante nelle Impostazioni rapide |
| `MainActivity.kt` | Schermata delle impostazioni |
| `config/signatures.json` | Firme modificabili da GitHub |
