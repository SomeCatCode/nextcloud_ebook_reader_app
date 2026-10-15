# Politique de confidentialité de l’application Android « E-Book Reader » (SomeCatCode)

Dernière mise à jour : 12 octobre 2026

## 1. Responsable du traitement

Felix Kurth
E-mail : it@wasmitleder.de

## 2. Résumé

L’application lit des livres numériques provenant de **dossiers de votre appareil que vous choisissez** et, en option, d’un **serveur Nextcloud que vous saisissez vous-même**. Le développeur n’exploite aucun serveur pour l’application et ne reçoit **aucune donnée** de votre part ni de votre appareil. L’application ne contient **aucune publicité, aucun suivi, aucune analyse et aucun rapport de plantage**.

## 3. Données traitées par l’application

Pour fonctionner, l’application traite sur votre appareil :

- **Identifiants :** l’adresse de votre serveur Nextcloud, votre nom d’utilisateur et un mot de passe d’application. Votre serveur crée le mot de passe d’application lorsque vous vous connectez via le navigateur (Nextcloud Login Flow). Il est stocké de manière chiffrée avec l’Android Keystore. L’application ne voit jamais votre véritable mot de passe Nextcloud.
- **Données de la bibliothèque :** titres, auteurs, séries, genres, étiquettes, couvertures et autres informations sur vos livres, ainsi que vos étagères.
- **Fichiers de livres** que vous téléchargez pour une lecture hors ligne, stockés dans l’espace de stockage privé de l’application.
- **Dossiers locaux :** lorsque vous ajoutez un dossier comme bibliothèque, l’application lit les fichiers de livres qu’il contient (via le sélecteur de dossiers d’Android, uniquement ce dossier). Les titres, couvertures et autres informations, la position de lecture et les surlignages d’une telle bibliothèque restent uniquement sur l’appareil. Ce n’est que lorsque vous copiez ou déplacez explicitement un livre vers un compte Nextcloud que le fichier, sa position de lecture et ses surlignages sont envoyés à ce serveur.
- **Données de lecture :** position de lecture, statut de lecture, notes, surlignages et annotations, ainsi qu’un nom d’appareil de votre choix (par défaut : le modèle de l’appareil) envoyé avec la position de lecture afin que vous puissiez voir sur vos autres appareils où vous vous êtes arrêté.
- **Paramètres de l’application** tels que le thème et les réglages de lecture. Ils restent sur l’appareil.

## 4. Où les données sont transmises

Les données ne sont transmises **qu’entre votre appareil et le serveur Nextcloud que vous saisissez dans l’application**, toujours chiffrées via HTTPS. L’exploitant de ce serveur est responsable du traitement qui y est effectué : vous-même si vous gérez le serveur, sinon votre fournisseur de cloud. Sa politique de confidentialité s’applique.

Les données ne sont jamais partagées avec le développeur ni avec un autre tiers.

Les liens contenus dans les livres ne s’ouvrent dans le navigateur de votre appareil qu’après votre confirmation. Les politiques de confidentialité des sites web que vous visitez s’appliquent alors.

## 5. Autorisations

- **Internet et état du réseau :** connexion à votre serveur ; téléchargements uniquement en Wi-Fi si vous le choisissez.
- **Notifications :** progression et fin des téléchargements. Vous pouvez les désactiver à tout moment dans les paramètres d’Android.
- **Service de premier plan (synchronisation de données) :** les téléchargements volumineux se poursuivent de manière fiable lorsque l’application est en arrière-plan.

L’application n’accède ni à votre position, ni à vos contacts, ni à votre appareil photo, ni à votre microphone, ni à vos autres fichiers.

## 6. Conservation et suppression

Toutes les données restent sur l’appareil jusqu’à ce que vous les supprimiez :

- **« Supprimer le compte »** dans l’application supprime toutes les données locales de ce compte (bibliothèque, livres téléchargés et identifiants) et révoque le mot de passe d’application sur votre serveur. Pour un dossier local, la bibliothèque, la position de lecture et les surlignages sont supprimés et l’accès au dossier est restitué ; les fichiers de livres du dossier sont conservés.
- **La désinstallation** de l’application supprime toutes les données locales.
- Les livres téléchargés peuvent être supprimés à tout moment sous « Téléchargements ».

Vous gérez les données de votre serveur Nextcloud, telles que la position de lecture, les surlignages et les étagères, sur le serveur lui-même.

## 7. Base juridique

Le traitement sur l’appareil sert uniquement à fournir les fonctionnalités que vous demandez (art. 6, par. 1, point b) du RGPD). Le développeur ne traite aucune donnée à caractère personnel.

## 8. Vos droits

En vertu du RGPD, vous disposez d’un droit d’accès, de rectification, d’effacement, de limitation du traitement, de portabilité des données et d’opposition, ainsi que du droit d’introduire une réclamation auprès d’une autorité de contrôle. Comme le développeur ne reçoit aucune donnée de votre part, veuillez vous adresser à l’exploitant de votre serveur pour les données qui y sont stockées. Pour toute question sur l’application, utilisez l’adresse e-mail ci-dessus.

## 9. Enfants

L’application ne s’adresse pas aux enfants de moins de 16 ans.

## 10. Modifications

Nous mettons à jour cette politique lorsque l’application ou la situation juridique évolue. La version publiée ici fait foi.
